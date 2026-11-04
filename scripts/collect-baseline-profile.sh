#!/usr/bin/env bash
# API 34+ manual capture from the real, non-minified/non-debuggable app.
# Build: ./android/gradlew -p android :app:assembleBaselineCapture
# 1. prepare APK [DEVICE] (updates the existing dogfood/debug app; retains data)
# 2. Exercise startup, connect to a test Host, open Chat and scroll, then Terminal.
# 3. collect [DEVICE] (writes android/app/src/main/baseline-prof.txt)
# Rebuild assembleDogfood afterwards; never publish the capture APK.
# https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
package=io.github.shiroel.aries.dev
mode=${1:?Usage: collect-baseline-profile.sh prepare APK [DEVICE] | collect [DEVICE]}
case "$mode" in
    prepare) apk=${2:?capture APK required}; device=${3:-emulator-5556} ;;
    collect) device=${2:-emulator-5556} ;;
    *) echo 'Expected prepare or collect' >&2; exit 2 ;;
esac
# Each invocation is a short batch. Do not hold the lock while navigating.
exec 9>/tmp/aries-mvp-run/emulator.lock
flock 9
call() { "$adb" -s "$device" "$@"; }
[[ $(call shell getprop ro.build.version.sdk | tr -d '\r') -ge 34 ]] || {
    echo 'Profile capture needs API 34+' >&2; exit 1;
}
if [[ $mode == prepare ]]; then
    call install -r "$apk"
    call shell am broadcast -a androidx.profileinstaller.action.SKIP_FILE --es EXTRA_SKIP_FILE_OPERATION WRITE_SKIP_FILE \
        "$package/androidx.profileinstaller.ProfileInstallReceiver"
    call shell am force-stop "$package"
    call shell cmd package compile -f -m verify "$package"
    call shell pm art clear-app-profiles "$package"
    call shell am start -W -n "$package/io.github.shiroel.aries.MainActivity"
else
    sleep 5 # Let ART stabilize the just-exercised journey before flushing.
    call shell am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE \
        "$package/androidx.profileinstaller.ProfileInstallReceiver"
    sleep 1
    call shell am force-stop "$package"
    call shell pm dump-profiles --dump-classes-and-methods "$package"
    profile=$(mktemp)
    trap 'rm -f "$profile"' EXIT
    call pull "/data/misc/profman/$package-primary.prof.txt" "$profile"
    # An R8-obfuscated input cannot be reused as a source profile.
    rg -q 'Lio/github/shiroel/aries/MainActivity;' "$profile" || {
        echo 'No unobfuscated MainActivity in profile; use baselineCapture and exercise startup first' >&2; exit 1;
    }
    # Compose/AndroidX already ship library profiles. Keep this app's generated
    # rules so the source profile stays reviewable and tracks our own journeys.
    rg '^[HSP]*Lio/github/shiroel/aries/' "$profile" | LC_ALL=C sort -u > "$root/android/app/src/main/baseline-prof.txt"
    call shell am broadcast -a androidx.profileinstaller.action.SKIP_FILE --es EXTRA_SKIP_FILE_OPERATION DELETE_SKIP_FILE \
        "$package/androidx.profileinstaller.ProfileInstallReceiver"
fi
