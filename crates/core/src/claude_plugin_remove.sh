# Works after the Beacon binary has disappeared; Claude owns its registry.
if command -v claude >/dev/null 2>&1; then
  claude plugin uninstall aries@aries-beacon >/dev/null 2>&1 || true
  claude plugin marketplace remove aries-beacon >/dev/null 2>&1 || true
fi
# Without a JSON reader, preserve the directory rather than guessing ownership.
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import json, os, pathlib, stat
root = pathlib.Path(os.environ.get('XDG_DATA_HOME') or pathlib.Path.home() / '.local/share') / 'aries-beacon/claude-marketplace'
names = ['.claude-plugin/marketplace.json', 'plugins/aries/.claude-plugin/plugin.json', 'plugins/aries/hooks/hooks.json']
try:
    if not stat.S_ISDIR(root.lstat().st_mode): raise ValueError('foreign root')
    marker = root / '.aries-managed.json'
    if not stat.S_ISREG(marker.lstat().st_mode): raise ValueError('foreign marker')
    record = json.loads(marker.read_text())
    if set(record) != set(names): raise ValueError('foreign marker')
    for name in names:
        path = root / name
        if not stat.S_ISREG(path.lstat().st_mode): raise ValueError('foreign file')
        if any(not stat.S_ISDIR(p.lstat().st_mode) for p in path.parents if p != root and root in p.parents): raise ValueError('foreign parent')
        if path.read_bytes() != record[name].encode(): raise ValueError('edited file')
    for name in names: (root / name).unlink()
    marker.unlink()
    for name in ['plugins/aries/.claude-plugin', 'plugins/aries/hooks', 'plugins/aries', 'plugins', '.claude-plugin', '']:
        try: (root / name).rmdir()
        except OSError: pass
except (OSError, ValueError, TypeError, AttributeError): pass
PY
fi
exit 0
