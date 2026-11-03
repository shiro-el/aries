#!/usr/bin/env bash
# Types Korean on the real Gboard into the Terminal input (KoreanImeDeviceTest)
# on a connected device or emulator: makes Gboard the keyboard for the run,
# then puts the previous keyboard back.
#
# Needs, once by hand: Gboard > Languages > Add keyboard > 한국어 두벌식, next
# to English (US) QWERTY; a 1080x2400 screen (the test taps key positions).
#
# Usage: scripts/test-korean-ime.sh
#   ANDROID_SERIAL  device to use (adb's default otherwise)
#   ARIES_ABIS      ABIs to build the core for (e.g. x86_64 for an emulator)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
gboard=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME

previous="$("$adb" shell settings get secure default_input_method | tr -d '\r')"
"$adb" shell ime enable "$gboard" >/dev/null
"$adb" shell ime set "$gboard" >/dev/null
trap '"$adb" shell ime set "$previous" >/dev/null 2>&1 || true' EXIT

"$adb" logcat -c
(cd "$root/android" && ./gradlew -q :app:connectedDebugAndroidTest \
  ${ARIES_ABIS:+-Paries.abis=$ARIES_ABIS} \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.shiroel.aries.KoreanImeDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.koreanIme=gboard)
"$adb" logcat -d -s AriesKoreanIme:I | grep 'sent:'
