#!/bin/sh
# Tests for scripts/release-version.sh: release tag -> versionName/versionCode.
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../release-version.sh"
fails=0

expect() { # expect TAG EXPECTED_OUTPUT
  got=$(sh "$script" "$1" 2>/dev/null) || got="<exit $?>"
  if [ "$got" != "$2" ]; then
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$1" "$2" "$got"
    fails=$((fails + 1))
  fi
}

expect_reject() { # expect_reject TAG
  if sh "$script" "$1" >/dev/null 2>&1; then
    printf 'FAIL %s: accepted, want rejected\n' "$1"
    fails=$((fails + 1))
  fi
}

# A final release sorts after all of its release candidates.
expect v0.1.0 'name=0.1.0 code=10099 prerelease=false'
expect v0.1.0-rc.1 'name=0.1.0-rc.1 code=10001 prerelease=true'
expect v0.1.0-rc.98 'name=0.1.0-rc.98 code=10098 prerelease=true'
expect v1.2.3 'name=1.2.3 code=1020399 prerelease=false'
# Every release must update over legacy Aries (same applicationId, versionCode 235).
expect v0.0.3 'name=0.0.3 code=399 prerelease=false'
expect v12.99.99 'name=12.99.99 code=12999999 prerelease=false'

expect_reject 0.1.0
expect_reject v0.1
expect_reject v0.1.0-beta
expect_reject v0.1.0-rc.0
expect_reject v0.1.0-rc.99
expect_reject v0.100.0
expect_reject v0.1.100
expect_reject v0.0.1
expect_reject v2100.0.0
expect_reject ''

[ "$fails" -eq 0 ] && echo 'release-version: ok'
exit "$fails"
