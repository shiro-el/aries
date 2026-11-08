#!/usr/bin/env bash
# Frame times while watching a fullscreen agent TUI (TerminalJankTest): starts
# the fixture sshd with the pinned herdr-core, puts a program there that draws
# like Claude Code's fullscreen renderer (alternate screen, box drawing,
# Hangul), runs the instrumented test and prints its result lines.
#
# Usage: scripts/measure-terminal-jank.sh [SECONDS]   (default 8 per scenario)
#   ANDROID_SERIAL  device to use (default emulator-5554)
# Installs the debug app and its test APK (kept installed afterwards) and holds
# the shared emulator lock only while the test runs.
set -euo pipefail

seconds="${1:-8}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bin="$("$root/scripts/fetch-herdr-core.sh")"
serial="${ANDROID_SERIAL:-emulator-5554}"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
name="${ARIES_TEST_CONTAINER_PREFIX:-aries-test}-jank-$$"
image="${ARIES_TEST_CONTAINER_PREFIX:-aries-test}-jank-sshd"

docker build -q -t "$image" "$root/fixtures/sshd" >/dev/null
docker run -d --rm --name "$name" -p "127.0.0.1::22" -v "$bin:/usr/local/bin/herdr-core:ro" "$image" >/dev/null
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
port="$(docker port "$name" 22/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 50); do
  docker exec "$name" sh -c 'nc -z 127.0.0.1 22' 2>/dev/null && break
  sleep 0.2
done

# Two prepared screens drawn in turn: `repaint` swaps all of them 20 times a
# second (an agent being scrolled), `spinner` changes one line 10 times a second.
docker exec -i "$name" sh -c 'cat > /home/bootstrap/fullscreen.sh && chown bootstrap /home/bootstrap/fullscreen.sh' <<'EOF'
set -- "$1" $(stty size)
mode=$1 rows=$2 cols=$3
screen() {
  awk -v R="$rows" -v C="$cols" -v o="$1" 'BEGIN {
    rule = ""; for (i = 0; i < C - 2; i++) rule = rule "─"
    for (r = 0; r < R - 1; r++) {
      t = (r + o) % 6
      if (t == 0) s = "╭" rule "╮"
      else if (t == 1) s = "│ ⏺ Read(crates/core/src/terminal/vt.rs) 터미널 스크롤 경로를 확인합니다 ✓"
      else if (t == 2) s = "│   ⎿  fn scroll_program(&self, lines: i32, column: u16, row: u16) -> Result<(), CoreError> {"
      else if (t == 3) s = "│ ● 한글 설명이 들어간 줄입니다. 마우스 휠과 PgUp/PgDn 으로 대화를 스크롤합니다 →"
      else if (t == 4) s = "│   let unit = if self.observe { (st.vt.rows() as i32 / 2).max(1) } else { WHEEL_LINES };"
      else s = "╰" rule "╯"
      printf "\033[%d;1H\033[2K\033[3%dm%s\033[0m", r + 1, (r + o) % 7 + 1, s
    }
  }'
}
screen 0 > /tmp/screen0; screen 3 > /tmp/screen1
printf '\033[?1049h\033[?1000h\033[?1006h\033[?25l'
i=0
while :; do
  i=$((i + 1))
  if [ "$mode" = repaint ]; then cat /tmp/screen$((i % 2)); fi
  if [ "$mode" = spinner ] && [ "$i" = 1 ]; then cat /tmp/screen0; fi
  printf '\033[%d;1H\033[2K✻ AriesJank %s %d (esc to interrupt)' "$rows" "$mode" "$i"
  if [ "$mode" = repaint ]; then sleep 0.05; else sleep 0.1; fi
done
EOF

# Not debuggable and compiled ahead of time, as a sideloaded dogfood build runs.
(cd "$root/android" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest -Paries.debuggable=false ${ARIES_ABIS:+-Paries.abis=$ARIES_ABIS})
exec 9>/tmp/aries-mvp-run/emulator.lock
flock 9
"$adb" -s "$serial" install -r "$root/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$adb" -s "$serial" install -r "$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
"$adb" -s "$serial" shell cmd package compile -m speed -f io.github.shiroel.aries.dev >/dev/null
"$adb" -s "$serial" logcat -c
"$adb" -s "$serial" shell am instrument -w \
  -e class io.github.shiroel.aries.TerminalJankTest \
  -e jankPort "$port" -e jankSeconds "$seconds" \
  io.github.shiroel.aries.dev.test/androidx.test.runner.AndroidJUnitRunner | tail -3
flock -u 9
"$adb" -s "$serial" logcat -d -s AriesJank:I | grep 'frames in'
# ARIES_JANK_SHOTS=DIR: also fetch what each scenario drew.
if [[ -n "${ARIES_JANK_SHOTS:-}" ]]; then
  mkdir -p "$ARIES_JANK_SHOTS"
  for mode in spinner repaint; do "$adb" -s "$serial" pull "/sdcard/Download/aries-jank-$mode.png" "$ARIES_JANK_SHOTS/" >/dev/null; done
fi
