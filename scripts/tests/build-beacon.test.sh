#!/bin/sh
# Build CLI preflight must fail before attempting a build on unsupported hosts.
set -eu
root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat >"$tmp/bin/uname" <<'EOF'
#!/bin/sh
echo Linux
EOF
for command in cargo rustup; do
  cat >"$tmp/bin/$command" <<'EOF'
#!/bin/sh
echo 'unexpected toolchain invocation' >&2
exit 99
EOF
done
chmod +x "$tmp/bin/"*
if PATH="$tmp/bin:$PATH" sh "$root/scripts/build-beacon.sh" "$tmp/out" macos-aarch64 >"$tmp/log" 2>&1; then
  echo 'FAIL: accepted macOS build on Linux'; exit 1
fi
grep -q 'macOS.*CI' "$tmp/log" || { cat "$tmp/log"; exit 1; }
if grep -q 'unexpected toolchain invocation' "$tmp/log"; then
  cat "$tmp/log"; exit 1
fi
if PATH="$tmp/bin:$PATH" CC_aarch64_unknown_linux_musl=aries-nonexistent-musl-cc \
  sh "$root/scripts/build-beacon.sh" "$tmp/out" linux-aarch64 >"$tmp/log" 2>&1; then
  echo 'FAIL: accepted missing musl C compiler'; exit 1
fi
grep -q 'missing musl C compiler.*beacon-build.Dockerfile' "$tmp/log" || { cat "$tmp/log"; exit 1; }
if grep -q 'unexpected toolchain invocation' "$tmp/log"; then
  cat "$tmp/log"; exit 1
fi
echo 'build-beacon: ok'
