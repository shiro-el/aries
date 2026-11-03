#!/usr/bin/env bash
# Downloads the pinned herdr-core release that the tests run against and
# checks it against a fixed SHA-256. Installs nothing outside the repo's
# target/ dir and never touches a running herdr-core.
#
# Usage: scripts/fetch-herdr-core.sh   -> prints the binary path
set -euo pipefail

version=0.1.17
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/target/herdr-core-$version"

if [[ -x "$dest/herdr-core" ]] && [[ "$("$dest/herdr-core" --version)" == "hc $version" ]]; then
  echo "$dest/herdr-core"
  exit 0
fi

os="$(uname -s)"
arch="$(uname -m)"
case "$os/$arch" in
  Linux/x86_64) platform=linux-x86_64; sha=82ec44043f6894bfdb716b979b87cda43fd7fa46b0104e7b7bd93e64d1a9b877 ;;
  Linux/aarch64 | Linux/arm64) platform=linux-aarch64; sha=3a37a454ec63494cf4306db26481fe9db96673d888723701d24f8a992ae8ab8c ;;
  Darwin/arm64) platform=macos-aarch64; sha=cedbd4d4d9ba1385588198dd4d3c67d2654478f33add5b6445d4d0eb9da5fcf3 ;;
  *) echo "no pinned herdr-core $version for $os/$arch" >&2; exit 1 ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL --retry 3 -o "$tmp/core.tar.gz" "https://releases.herdr.dev/core/$version/herdr-core-$platform.tar.gz"
got="$( (sha256sum "$tmp/core.tar.gz" 2>/dev/null || shasum -a 256 "$tmp/core.tar.gz") | cut -d' ' -f1)"
if [[ "$got" != "$sha" ]]; then
  echo "herdr-core $version checksum mismatch: got $got" >&2
  exit 1
fi
mkdir -p "$dest"
tar -xzf "$tmp/core.tar.gz" -C "$dest"
echo "$dest/herdr-core"
