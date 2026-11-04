# Aries companion for Wear OS 4+

The phone owns SSH and Last-known state. The launcher opens a Wear Compose Inbox:
requests needing attention first, then Agents grouped by Host and Space (current
folder when no Space is known). Each row opens request text and a short excerpt
of the last cached Agent response. Missing Chat content directs the user to the
phone. Refresh asks the phone's existing WorkManager pull to run; acknowledgment
means scheduled, and only a new DataItem advances the displayed fetch time.
Biometric-key Hosts still require the foreground phone app to refresh.

The v2 contract retains the v1 envelope and its three-Agent projection, adding
`inbox_version=2` and an `inbox` list. Old watches ignore the extension; new
watches also accept v1-only phones and explicit v2 envelopes. The Inbox carries
up to 32 Agents, request text up to 1,200 UTF-16 units, and last-response excerpts
up to 600. The entire DataMap stays within 100 KB; a large list is shortened and
the screen directs users to the phone for the rest. Names, folders, Choices and
request IDs are bounded too. Preview protection on the phone removes request
and conversation content before publication. No SSH credentials or full
transcripts are transferred.
It works with an Android phone paired through the normal Wear OS pairing flow.
Tile taps send a best-effort MessageClient command to that phone; there is no
offline command queue. The phone still applies its usual Host/key checks.

## Build and install with Wi-Fi ADB

Build both APKs from the same checkout and signing environment:

```sh
cd android
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
./gradlew :app:assembleDebug :wear:assembleDebug
```

Both debug APKs use `io.github.shiroel.aries.dev` and the same default Android
debug keystore. Release builds use `io.github.shiroel.aries` and the shared
`ARIES_UPLOAD_*` signing configuration documented in `CONTRIBUTING.md`. An
unsigned release cannot use the Data Layer with a signed phone install. A Play
phone install needs a watch APK signed with that same app signing certificate,
not merely the upload certificate.

On the watch, enable Developer options (About watch → Software → tap Software
version five times), ADB debugging and Wireless debugging. Put the workstation
and watch on the same Wi-Fi. Select **Pair new device** and read the pairing IP,
port and code. The pairing port differs from the ordinary debugging port.
These pairing steps are for the user to perform. Development screenshots use a
round Wear OS emulator with fictional seeded state, not a paired physical watch.

```sh
# Replace every placeholder. Enter the pairing code at the prompt.
flock /tmp/aries-mvp-run/emulator.lock "$ANDROID_HOME/platform-tools/adb" pair WATCH_IP:PAIR_PORT
flock /tmp/aries-mvp-run/emulator.lock "$ANDROID_HOME/platform-tools/adb" connect WATCH_IP:DEBUG_PORT
flock /tmp/aries-mvp-run/emulator.lock "$ANDROID_HOME/platform-tools/adb" devices
flock /tmp/aries-mvp-run/emulator.lock "$ANDROID_HOME/platform-tools/adb" -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
flock /tmp/aries-mvp-run/emulator.lock "$ANDROID_HOME/platform-tools/adb" -s WATCH_IP:DEBUG_PORT install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Open Aries on the phone and fetch a Host once. Add the Aries Tile to the watch's
carousel and select Aries as a Short Text complication on a compatible watch
face. Confirm the count, three Agent rows, age and Agent tap on the phone. Check
again disconnected: the last value and its age remain, while a failed tap asks
for the phone connection. The complication refresh interval is 300 seconds;
Data Layer updates refresh the Tile but do not push complication updates more
frequently. A never-synced complication displays `--` rather than a false zero.

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the existing install uses a different
certificate; use matching builds. Do not uninstall another user's install as a
workaround. No Firebase/Cloudflare deployment is needed for this companion.

`WatchInboxActivity` accepts `host` and `terminal` extras for local detail links.
`WatchInbox` exposes a composable `detailActions(WatchAgent)` slot; Answer, Stop,
and progress are disabled placeholders until their dedicated action tickets land.
The existing Tile/complication implementations remain separate.

References: [Wear Compose](https://developer.android.com/jetpack/androidx/releases/wear-compose),
[Data Layer](https://developer.android.com/training/wearables/data/overview),
[Tiles](https://developer.android.com/training/wearables/tiles),
[complications](https://developer.android.com/training/wearables/complications/exposing-data),
[Samsung Wi-Fi debugging](https://developer.samsung.com/health/sensor/guide/connect-watch.html).
