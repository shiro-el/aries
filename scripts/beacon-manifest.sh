#!/bin/sh
# Writes the Beacon release manifest and checksums for a release tag, from the
# binaries scripts/build-beacon.sh put in DIR (aries-beacon-<os>-<arch>):
#
#   scripts/beacon-manifest.sh v0.1.0 dist/beacon
#
# DIR/beacon-release.json is what the app pins: build the core with
# ARIES_BEACON_MANIFEST=DIR/beacon-release.json (crates/core/build.rs) and it
# installs exactly these binaries, checked against these SHA-256s.
# DIR/beacon-SHA256SUMS ships on the GitHub Release next to the binaries.
#
# URLs point at the tag's GitHub Release (GITHUB_REPOSITORY, default
# shiro-el/aries); ARIES_BEACON_BASE_URL overrides the base (tests). All four
# targets are required unless ARIES_BEACON_PARTIAL=1 (a local build).
set -eu

tag=${1:?usage: beacon-manifest.sh TAG DIR}
dir=${2:?usage: beacon-manifest.sh TAG DIR}
root=$(cd "$(dirname "$0")/.." && pwd)
die() { echo "beacon-manifest: $*" >&2; exit 1; }

sh "$root/scripts/release-version.sh" "$tag" >/dev/null
version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$root/Cargo.toml" | head -n1)
tag_version=${tag#v}
tag_version=${tag_version%%-*}
# The installer checks `aries-beacon --version` against the pinned version.
[ "$tag_version" = "$version" ] || die "tag $tag is not the workspace version $version (bump Cargo.toml first)"

base=${ARIES_BEACON_BASE_URL:-https://github.com/${GITHUB_REPOSITORY:-shiro-el/aries}/releases/download/$tag}

sums="$dir/beacon-SHA256SUMS"
manifest="$dir/beacon-release.json"
: >"$sums.tmp"
entries=""
for target in linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64; do
  name="aries-beacon-$target"
  if [ ! -f "$dir/$name" ]; then
    [ "${ARIES_BEACON_PARTIAL:-}" = 1 ] || { rm -f "$sums.tmp"; die "missing $dir/$name"; }
    continue
  fi
  sum=$(cd "$dir" && sha256sum "$name")
  echo "$sum" >>"$sums.tmp"
  entry=$(printf '    {"os": "%s", "arch": "%s", "url": "%s/%s", "sha256": "%s"}' \
    "${target%%-*}" "${target#*-}" "$base" "$name" "${sum%% *}")
  entries="${entries:+$entries,
}$entry"
done
[ -n "$entries" ] || { rm -f "$sums.tmp"; die "no Beacon binaries in $dir"; }
mv "$sums.tmp" "$sums"
printf '{\n  "version": "%s",\n  "artifacts": [\n%s\n  ]\n}\n' "$version" "$entries" >"$manifest"
echo "beacon-manifest: wrote $manifest"
