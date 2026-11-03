#!/usr/bin/env bash
# Answers a Call notification with the phone locked (CallNotificationDeviceTest,
# ticket 36) on a connected device or emulator, against a docker sshd Host
# whose fake Beacon records every `reply` and says `delivered` the first
# time, `answered` after:
#   1. `locked`: screen off, PIN keyguard up, the button sent as SystemUI does;
#      delivered once, the second tap refused;
#   2. `setup`, then the app process is killed, PIN 3636 set, the screen
#      turned off and on, and "Stop" tapped on the lock screen; `check`.
# The Host must see exactly one reply per step. The PIN and the container
# are removed on exit.
#
# Usage: scripts/test-call-notification.sh
#   ANDROID_SERIAL  device to use (adb's default otherwise)
#   ARIES_ABIS      ABIs to build the core for (e.g. x86_64 for an emulator)
#   ARIES_CALL_HOST address the phone reaches this computer at (default
#                   10.0.2.2, the emulator's; a phone needs this computer's
#                   LAN or Tailscale address, and the sshd published there)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
pkg=io.github.shiroel.aries.dev
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
test=io.github.shiroel.aries.CallNotificationDeviceTest
pin=3636
address="${ARIES_CALL_HOST:-10.0.2.2}"
publish=127.0.0.1
[ "$address" = 10.0.2.2 ] || publish="$address"
container="${ARIES_TEST_CONTAINER_PREFIX:-aries-test}-callnotify-$$"
work="$(mktemp -d)"

cleanup() {
  "$adb" shell locksettings clear --old "$pin" >/dev/null 2>&1 || true
  "$adb" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$adb" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "$adb" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  "$adb" shell rm -f /sdcard/aries-callnotify.xml >/dev/null 2>&1 || true
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---- the Host ----------------------------------------------------------------
image="aries-callnotify-sshd"
docker build -q -t "$image" "$root/fixtures/sshd" >/dev/null
docker run -d --rm --name "$container" -p "$publish::22" "$image" >/dev/null
port="$(docker port "$container" 22/tcp | head -1 | sed 's/.*://')"
docker exec -i "$container" sh -c 'mkdir -p /home/aries/.local/bin && cat > /home/aries/.local/bin/aries-beacon && chmod 755 /home/aries/.local/bin/aries-beacon && chown -R aries /home/aries/.local' <<'EOF'
#!/bin/sh
log="$HOME/replies.log"
if [ -s "$log" ]; then printf '%s\n' '{"v":1,"outcome":"answered"}'; exit 1; fi
echo "$@" >> "$log"
printf '%s\n' '{"v":1,"outcome":"delivered"}'
EOF
fingerprint="$(docker exec "$container" ssh-keygen -l -f /etc/ssh/ssh_host_ed25519_key.pub | awk '{print $2}')"
replies() { docker exec "$container" sh -c 'cat /home/aries/replies.log 2>/dev/null || true'; }

# ---- the app -------------------------------------------------------------------
(cd "$root/android" && ./gradlew -q :app:installDebug :app:installDebugAndroidTest \
  ${ARIES_ABIS:+-Paries.abis=$ARIES_ABIS})

instrument() {
  local out
  out="$("$adb" shell am instrument -w -e class "$test#$1" -e step "$1" \
    -e callHostAddress "$address" -e callHostPort "$port" -e callHostKey "$fingerprint" "$runner" | tr -d '\r')"
  echo "$out" | grep -q '^OK (1 test)' || { echo "$out" >&2; echo "step $1 failed" >&2; exit 1; }
  echo "step $1: ok"
}

instrument key
key="$("$adb" shell run-as "$pkg" cat files/core/identity.pub | tr -d '\r')"
docker exec -e "KEY=$key" "$container" sh -c 'printf "%s\n" "$KEY" >> /home/aries/.ssh/authorized_keys'

# ---- 1: locked, screen off, PendingIntent sent ------------------------------------
instrument locked
[ "$(replies)" = "reply c36 1" ] || { echo "the Host got: $(replies)" >&2; exit 1; }
echo "Host replies: $(replies)"
docker exec "$container" rm -f /home/aries/replies.log

# ---- 2: process killed, real tap on the PIN lock screen ----------------------------
instrument setup
"$adb" shell input keyevent KEYCODE_HOME
sleep 1
"$adb" shell am kill "$pkg"
sleep 1
if "$adb" shell pidof "$pkg" >/dev/null; then echo "the app process is still running" >&2; exit 1; fi
echo "app process: killed"
"$adb" shell dumpsys notification --noredact | grep -q 'tag=call:c36' || { echo "the notification is gone" >&2; exit 1; }

"$adb" shell locksettings set-pin "$pin" >/dev/null
"$adb" shell input keyevent KEYCODE_SLEEP
sleep 2
"$adb" shell input keyevent KEYCODE_WAKEUP
sleep 2
"$adb" shell dumpsys window | grep -o 'isKeyguardShowing=[a-z]*' | head -1

dump() { "$adb" shell uiautomator dump /sdcard/aries-callnotify.xml >/dev/null; "$adb" shell cat /sdcard/aries-callnotify.xml | sed 's/></>\n</g' > "$work/$1"; }
bounds() { grep -m1 "$1" "$work/$2" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | paste -sd' ' || true; }
center() { set -- $1; echo $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); }

dump lock1.xml
stop="$(bounds 'text="Stop"' lock1.xml)"
if [ -z "$stop" ]; then
  expand="$(bounds 'resource-id="android:id/expand_button"' lock1.xml)"
  [ -n "$expand" ] && { "$adb" shell input tap $(center "$expand"); sleep 2; }
  dump lock2.xml
  stop="$(bounds 'text="Stop"' lock2.xml)"
fi
[ -n "$stop" ] || { echo "no Stop button on the lock screen (see $work)" >&2; exit 1; }
"$adb" shell input tap $(center "$stop")
echo "tapped Stop on the lock screen"
for _ in $(seq 1 40); do [ -n "$(replies)" ] && break; sleep 0.5; done
sleep 2
"$adb" shell dumpsys window | grep -o 'isKeyguardShowing=[a-z]*' | head -1
"$adb" shell dumpsys notification --noredact | grep -A60 'tag=call:c36' | grep -m1 -o 'android.text=String ([^)]*)' || true
[ "$(replies)" = "reply c36 1" ] || { echo "the Host got: $(replies)" >&2; exit 1; }
echo "Host replies: $(replies)"

"$adb" shell locksettings clear --old "$pin" >/dev/null
instrument check
echo "answered from the lock screen with the app process killed"
