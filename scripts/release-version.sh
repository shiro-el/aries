#!/bin/sh
# Maps a release tag to the Android version: `vX.Y.Z` or `vX.Y.Z-rc.N`.
# Prints `name=<versionName> code=<versionCode> prerelease=<true|false>`.
#
# versionCode = X*1000000 + Y*10000 + Z*100 + (N for rc.N, 99 for the final),
# so a final release sorts after its release candidates. It must stay above
# 235, the last legacy Aries build (same applicationId and upload key), so
# installs update over legacy, and at most 2100000000 (Play's limit).
set -eu

tag=${1:-}
die() { echo "release-version: $*" >&2; exit 1; }

version=${tag#v}
[ "$version" != "$tag" ] || die "tag must start with v: '$tag'"
echo "$version" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-rc\.[1-9][0-9]?)?$' \
  || die "tag must be vX.Y.Z or vX.Y.Z-rc.N (Y, Z <= 99, 1 <= N <= 98): '$tag'"

core=${version%%-*}
major=${core%%.*}
rest=${core#*.}
minor=${rest%%.*}
patch=${rest#*.}
case $version in
  *-rc.*) build=${version##*-rc.}; prerelease=true ;;
  *) build=99; prerelease=false ;;
esac
if [ "$prerelease" = true ] && [ "$build" -gt 98 ]; then die "rc number must be <= 98: '$tag'"; fi

code=$((major * 1000000 + minor * 10000 + patch * 100 + build))
[ "$code" -gt 235 ] || die "versionCode $code must be above legacy's 235: '$tag'"
[ "$code" -le 2100000000 ] || die "versionCode $code exceeds Play's 2100000000: '$tag'"

echo "name=$version code=$code prerelease=$prerelease"
