#!/bin/sh
# Tests for scripts/beacon-manifest.sh: built Beacon binaries -> the release
# manifest the app pins (crates/core/build.rs) and the SHA256SUMS a GitHub
# Release ships.
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../beacon-manifest.sh"
fails=0
fail() { printf 'FAIL %s\n' "$*"; fails=$((fails + 1)); }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$here/../../Cargo.toml" | head -n1)

dist="$tmp/full"
mkdir -p "$dist"
for t in linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64; do
  printf 'beacon %s' "$t" >"$dist/aries-beacon-$t"
done

# Known digests, from `printf 'beacon linux-x86_64' | sha256sum` etc.
want_linux_x86=$(printf 'beacon linux-x86_64' | sha256sum | cut -c1-64)

if ! GITHUB_REPOSITORY=acme/aries sh "$script" "v$version" "$dist" >/dev/null 2>"$tmp/err"; then
  fail "full set rejected: $(cat "$tmp/err")"
else
  m="$dist/beacon-release.json"
  grep -q "\"version\": \"$version\"" "$m" || fail "manifest version: $(cat "$m")"
  grep -q "\"url\": \"https://github.com/acme/aries/releases/download/v$version/aries-beacon-linux-x86_64\"" "$m" \
    || fail "manifest url: $(cat "$m")"
  grep -q "\"sha256\": \"$want_linux_x86\"" "$m" || fail "manifest sha256: $(cat "$m")"
  [ "$(grep -c '"os"' "$m")" = 4 ] || fail "want 4 artifacts: $(cat "$m")"
  # SHA256SUMS checks with the stock tool, from the release directory.
  (cd "$dist" && sha256sum -c --quiet beacon-SHA256SUMS) || fail "beacon-SHA256SUMS does not verify"
  [ "$(wc -l <"$dist/beacon-SHA256SUMS")" = 4 ] || fail "want 4 sums"
fi

# A release candidate tag points at its own release but pins the crate version.
dist_rc="$tmp/rc"
cp -r "$dist" "$dist_rc"
GITHUB_REPOSITORY=acme/aries sh "$script" "v$version-rc.2" "$dist_rc" >/dev/null 2>&1 || fail "rc tag rejected"
grep -q "releases/download/v$version-rc.2/aries-beacon-macos-aarch64\"" "$dist_rc/beacon-release.json" \
  || fail "rc url: $(cat "$dist_rc/beacon-release.json")"
grep -q "\"version\": \"$version\"" "$dist_rc/beacon-release.json" || fail "rc version"

# A base URL override (a local fake release server in tests).
ARIES_BEACON_BASE_URL=http://127.0.0.1:9/r sh "$script" "v$version" "$dist" >/dev/null 2>&1
grep -q '"url": "http://127.0.0.1:9/r/aries-beacon-linux-aarch64"' "$dist/beacon-release.json" || fail "base url override"

# The tag must be the version the Beacon reports, or installs fail `verify`.
sh "$script" v99.0.0 "$dist" >/dev/null 2>&1 && fail "accepted a tag that is not the crate version"

# A missing target fails the release, unless a partial (local) build is asked for.
part="$tmp/part"
mkdir -p "$part"
cp "$dist/aries-beacon-linux-x86_64" "$part/"
sh "$script" "v$version" "$part" >/dev/null 2>&1 && fail "accepted a release without all 4 targets"
ARIES_BEACON_PARTIAL=1 sh "$script" "v$version" "$part" >/dev/null 2>&1 || fail "partial build rejected"
[ "$(grep -c '"os"' "$part/beacon-release.json" 2>/dev/null)" = 1 ] || fail "partial manifest"

if [ "$fails" -gt 0 ]; then
  echo "beacon-manifest: $fails failure(s)"
  exit 1
fi
echo "beacon-manifest: ok"
