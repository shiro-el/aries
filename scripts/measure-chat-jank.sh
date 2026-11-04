#!/usr/bin/env bash
# Build first: ANDROID_HOME=~/Android/Sdk ./android/gradlew -p android :app:assembleChatProfile -Paries.abis=x86_64
# The profile APK uses a fake core and needs no Rust JNI library. It is R8-optimized/non-debuggable.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out=${1:?Usage: measure-chat-jank.sh OUTPUT_DIRECTORY [DEVICE] [APK] [SCENARIOS]}
device=${2:-emulator-5556}
apk=${3:-$root/android/app/build/outputs/apk/chatProfile/app-chatProfile.apk}
scenarios=${4:-"scroll stream reading"}
# Long pasted user prompts exercise full-history source inspection as well as Agent output.
prompt_repeats=${ARIES_CHAT_PROFILE_PROMPT_REPEATS:-0}
if [[ ! $prompt_repeats =~ ^[0-9]{1,4}$ ]] || ((10#$prompt_repeats > 1000)); then
    printf 'ARIES_CHAT_PROFILE_PROMPT_REPEATS must be an integer from 0 to 1000\n' >&2
    exit 2
fi
mkdir -p "$out"
out=$(cd "$out" && pwd)
adb=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
package=io.github.shiroel.aries.chatprofile
# Hold the shared emulator only for installation or one short scenario at a time.
exec 9>/tmp/aries-mvp-run/emulator.lock
flock 9
call() { "$adb" -s "$device" "$@"; }
cleanup() { call shell am force-stop "$package" >/dev/null; }
trap 'flock 9; cleanup' EXIT
call install -r "$apk" > "$out/install.txt"
call shell dumpsys package "$package" > "$out/package.txt"
call shell getprop ro.build.fingerprint > "$out/device.txt"
call shell wm size >> "$out/device.txt"
call shell wm density >> "$out/device.txt"
call shell dumpsys display >> "$out/device.txt"
# Compile the same way for both revisions; exclude cold/JIT compilation from the hot-path comparison.
call shell cmd package compile -m speed -f "$package" > "$out/compile.txt"
printf '%s\n' "$prompt_repeats" > "$out/prompt-repeats.txt"
flock -u 9
for scenario in $scenarios; do
    case "$scenario" in scroll|stream|reading) ;; *) exit 2 ;; esac
    flock 9
    printf 'Measuring %s (prompt repeats: %s)\n' "$scenario" "$prompt_repeats"
    cleanup
    call shell am start -W -n "$package/io.github.shiroel.aries.ui.ChatProfileActivity" --ei prompt-repeats "$prompt_repeats" > "$out/$scenario-launch.txt"
    sleep 8
    call shell pidof "$package" > "$out/$scenario-pid.txt"
    if [[ $scenario == reading ]]; then
        for ((i=0; i<6; i++)); do call shell input swipe 500 400 500 1600 300; done
        sleep 2
    fi
    call shell dumpsys gfxinfo "$package" reset > /dev/null
    stream=false
    if [[ $scenario != scroll ]]; then stream=true; fi
    call shell am broadcast -a "$package.PROFILE" -p "$package" --es command reset --ez stream "$stream" > /dev/null
    if [[ $scenario == scroll ]]; then
        for ((i=0; i<12; i++)); do call shell input swipe 500 400 500 1600 500; done
        for ((i=0; i<12; i++)); do call shell input swipe 500 1600 500 400 500; done
    else
        sleep 22
    fi
    call shell am broadcast -a "$package.PROFILE" -p "$package" --es command dump > /dev/null
    call shell dumpsys gfxinfo "$package" > "$out/$scenario-gfxinfo.txt"
    call shell dumpsys gfxinfo "$package" framestats > "$out/$scenario-framestats.txt"
    # Keep the latest reset/dump window without clearing other lanes' logcat buffers.
    call logcat -d -s ChatProfile:I '*:S' | awk '/ READY$/ { buffer="" } { buffer=buffer $0 "\n" } END { printf "%s", buffer }' > "$out/$scenario-composition.txt"
    rg -q ' DONE$' "$out/$scenario-composition.txt"
    call exec-out screencap -p > "$out/$scenario.png"
    cleanup
    flock -u 9
done
