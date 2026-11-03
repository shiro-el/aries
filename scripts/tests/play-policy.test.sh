#!/bin/sh
# Tests for scripts/check-play-policy.py against manifest fixtures:
# ok-*.xml must pass; bad-*.xml must fail with the message in their `expect:` comment.
set -u
here=$(cd "$(dirname "$0")" && pwd)
check="$here/../check-play-policy.py"
fails=0

for f in "$here"/play-policy/*.xml; do
  name=$(basename "$f")
  out=$(python3 -I "$check" "$f" 2>&1)
  status=$?
  case $name in
    ok-*)
      if [ "$status" -ne 0 ]; then
        printf 'FAIL %s: rejected\n%s\n' "$name" "$out"; fails=$((fails + 1))
      fi ;;
    bad-*)
      want=$(sed -n 's/^<!-- expect: \(.*\) -->$/\1/p' "$f")
      if [ "$status" -eq 0 ]; then
        printf 'FAIL %s: accepted\n' "$name"; fails=$((fails + 1))
      elif ! printf '%s' "$out" | grep -qF -- "$want"; then
        printf 'FAIL %s: want message containing "%s"\n%s\n' "$name" "$want" "$out"; fails=$((fails + 1))
      fi ;;
  esac
done

[ "$fails" -eq 0 ] && echo 'play-policy: ok'
exit "$fails"
