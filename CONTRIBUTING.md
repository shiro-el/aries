# Contributing to Aries

## Layout

| Path | What |
|---|---|
| `crates/core` | `aries-core`: the phone core. The only boundary the app calls, through uniffi. `src/transport/` (SSH, local child process), `src/hc.rs` (herdr-core client), `src/store.rs` (state that survives a relaunch). |
| `crates/protocol` | `aries-protocol`: the core <-> Beacon contract (shared formats and version rules). |
| `crates/beacon` | `aries-beacon`: the program that runs on a Host. |
| `crates/test-support` | Test harness: isolated herdr-core sessions (`HcSession`) and docker sshd Hosts (`SshdHost`). |
| `crates/uniffi-bindgen` | Generates the Kotlin bindings for `aries-core`. |
| `android/` | Kotlin + Jetpack Compose app (`io.github.shiroel.aries`). |
| `fixtures/` | Test fixtures (`fixtures/sshd/Dockerfile`; `fixtures/sshd-systemd/Dockerfile`, a systemd Host for the Beacon install tests). |
| `scripts/fetch-herdr-core.sh` | Downloads the pinned herdr-core the tests run against. |

## Requirements

- Rust (stable, edition 2024, 1.99+), Java 21, Android SDK with NDK 27.1.12297006.
- For Android builds: `rustup target add aarch64-linux-android x86_64-linux-android` and `cargo install cargo-ndk`; `ANDROID_HOME` (or `android/local.properties` `sdk.dir`) and `ANDROID_NDK_HOME` set.
- Docker for the SSH tests.

## Checks

```sh
scripts/fetch-herdr-core.sh                         # once: pinned herdr-core 0.1.17 into target/
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace                              # unit + core API tests against real herdr-core
cargo test -p aries-core -- --ignored              # SSH, jump host, password bootstrap + Beacon tests (docker)
for t in scripts/tests/*.test.sh; do sh "$t"; done  # release script tests
cd android && ./gradlew :app:testDebugUnitTest      # Compose UI tests against a fake core (Robolectric)
cd android && ./gradlew :app:assembleDebug          # debug APK (builds the core with cargo-ndk)
cd android && ./gradlew :app:connectedDebugAndroidTest   # device tests (Android Keystore); needs a device/emulator
cd relay && npm ci --legacy-peer-deps && npm run typecheck && npm test   # Relay HTTP API (Workers runtime, fake FCM)
```

`-Paries.abis=x86_64` limits the Android build to one ABI (faster on an emulator);
`-Paries.rustProfile=debug` builds the core unoptimised.

Core tests never touch your own herdr-core: each `HcSession` uses its own
`HC_SESSION` and puts socket, state and config under a temp dir, and strips
inherited `HC_*` variables. Set `ARIES_HERDR_CORE` to use another binary of the
same pinned version. Docker containers are named `aries-test-sshd-*`
(`ARIES_TEST_CONTAINER_PREFIX` overrides the prefix) and removed when the test ends.

## Tests

Tests go at the seams in `.scratch/aries-mvp/spec.md`:

1. **core API**: `crates/*/tests/` call `aries-core`'s public API against a
   real herdr-core (`HcSession`, local child-process transport). SSH itself is
   tested against `SshdHost`.
2. **Relay HTTP API** (Workers test runtime).
3. **Android UI <-> core API**: `android/app/src/test/` Compose tests against a
   fake implementation of the generated `AriesCoreInterface`.

## Host removal

`AriesCore::remove_host` runs the cleanup steps in `crates/core/src/removal.rs`,
then drops the Host record (connection settings and trusted Host key). A ticket
that creates something for one Host (pairing, Chat cache, ...) adds its own
idempotent step to `removal::STEPS` and tests the removal at the core API seam.
Per-Host files belong under `Store::host_dir` (`hosts/<id>/`), which the
existing step already deletes. Details are in the module doc.

## UI strings

Every user-visible string is an Android string resource
(`android/app/src/main/res/values/strings.xml`), translated by adding
`values-<lang>/strings.xml`. Keys are shared with the future iOS app, so they
follow one rule:

- `<area>_<thing>[_<role>]`, lowercase `snake_case`, ASCII only.
- `<area>` is the screen or feature (`hosts`, `host_add`, `host_key`, `key`,
  `terminals`, `connect`, `incompatible`, ...). Shared words use no area:
  `ok_button`, `cancel_button`, `back_button`.
- `<role>` says what the string is when that is not obvious: `_title`,
  `_body`, `_button`, `_hint`, `_label`.
- Format arguments are positional (`%1$s`, `%2$d`) so translations can reorder
  them; iOS maps them to `%1$@` / `%2$d`.

English (`values/`) is the fallback for any device language Aries lacks.
Korean (`values-ko/`) ships and must stay complete: **a new string needs its
Korean translation in the same change.** Brand names and pure format strings
can be `translatable="false"` instead. `TranslationsTest` (part of
`:app:testDebugUnitTest`) fails when a translation lacks a key or has different
format arguments, when an argument is not positional, or when Kotlin passes a
string literal to UI text (`Text("…")`, `label = "…"`; mark a deliberate one
with `// i18n-ok`).

To add a language, add only `android/app/src/main/res/values-<lang>/strings.xml`
(e.g. `values-ja`, `values-pt-rBR`) with every translatable key, and run
`:app:testDebugUnitTest`. No code changes: the per-app language list (Android
13+) is generated from the `values-*` directories. To make the test require it
from then on, add the language to `shipped` in `TranslationsTest`.

## Beacon on a Host

`crates/core/src/beacon.rs` installs it over SSH: binary
`~/.local/bin/aries-beacon`; on Linux a systemd user unit
`~/.config/systemd/user/aries-beacon.service`, on macOS a LaunchAgent
`~/Library/LaunchAgents/io.github.shiroel.aries.beacon.plist`; private state
(Call records) in `~/.local/state/aries-beacon/`. The Host downloads the
binary itself when it can (curl/wget) and the phone uploads it otherwise;
either way it must match the pinned SHA-256. `check_beacon` (called on
connect) updates an out-of-date Beacon unless automatic updates are off.
Removal runs `UNINSTALL_STEPS` and keeps
the state directory. A ticket that puts something else on the Host (pairing,
managed skills) adds its own removal step there and tests it in
`crates/core/tests/beacon.rs`. The pinned release (`pinned_release`) and its
checksums come from the Beacon release pipeline (below); tests swap it with
`AriesCore::set_beacon_release` and serve binaries from
`aries_test_support::ReleaseServer` (an `nginx:alpine` container).

## Push and the Relay

Turning push on (`AriesCore::enable_push`, `crates/core/src/push.rs`)
registers the phone on the Relay, runs `aries-beacon pair` over SSH (key
exchange) and `aries-beacon push-test`. The envelope format is
`aries_protocol::push` (version byte + HPKE auth mode + fixed 2048 bytes).
The Beacon sends with `push::send(&PushMessage)` (`crates/beacon/src/push.rs`);
a new notification kind is a new `PushMessage` variant plus its
`PushContent` in the core. Turning push off, Host removal and Beacon removal
delete the pairing on the Relay (removal queues it; the app sends the queue
with `flush_push_revocations`).

Agent status pushes: `aries-beacon serve` subscribes to the local
herdr-core's events (plain mode), tracks status with
`aries_beacon::status_watch` (the shared `agent_status` rule; top-level =
Terminal without `parent`; an Agent that stays Blocked for the same reason
or stays in Error is pushed once; the first snapshot after start is a
baseline) and sends `PushMessage::Status` for what
`aries_protocol::push_policy::PushPolicy` allows (default Blocked + Error,
top-level only; `<state>/push-policy.json` overrides). The real Claude Code
check: `ARIES_REAL_CLAUDE=1 cargo test -p aries-beacon --test status_push -- --ignored real_claude`.

The Relay is `relay/` (Cloudflare Worker + D1, TypeScript). It stores only
pairing token hashes, device push tokens and rate limit counters, and never
stores or logs an envelope.

```sh
cd relay && npm ci --legacy-peer-deps && npm run typecheck && npm test   # Workers runtime, fake FCM
```

Core tests use `aries_test_support::FakeRelay` (records envelopes) and, for a
docker Host, `SystemdHost::reverse_tunnel` so the Beacon in the container
reaches it. Deploying (done by a human, never by tests):

```sh
cd relay
node scripts/deploy-config.mjs      # needs ARIES_CF_D1_NAME, ARIES_CF_D1_ID, ARIES_FCM_SERVICE_ACCOUNT_EMAIL
npx wrangler d1 migrations apply "$ARIES_CF_D1_NAME" --remote -c wrangler.deploy.jsonc
npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON -c wrangler.deploy.jsonc < service-account.json
npx wrangler deploy -c wrangler.deploy.jsonc
```

Wrangler reads `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN`. The app
gets its Relay URL at build time from `ARIES_RELAY_URL` (repo variable in CI,
or `-Paries.relayUrl`), and Firebase from `android/app/google-services.json`
(never committed); without either the app says push is unavailable.

## Releases

Pushing a tag `vX.Y.Z` (or `vX.Y.Z-rc.N`, a prerelease) runs
`.github/workflows/release.yml`: it builds the APK and AAB signed with the
upload key, checks the signature and the Play policy rules
(`scripts/check-play-policy.py`), publishes the APK, `SHA256SUMS` and the
third-party notices on GitHub Releases, and uploads the AAB to Play internal
testing when the repo variable `PLAY_UPLOAD_ENABLED` is `true` (service
account in the secret `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`; release status from
`PLAY_RELEASE_STATUS`, default `draft`). `scripts/release-version.sh` maps the
tag to versionName/versionCode; every versionCode is above legacy Aries' 235.

CI secrets: `ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_STORE_PASSWORD`,
`ANDROID_UPLOAD_KEY_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`,
`GOOGLE_SERVICES_JSON_BASE64`. A local signed build reads the key from
`ARIES_UPLOAD_KEYSTORE`, `ARIES_UPLOAD_STORE_PASSWORD`, `ARIES_UPLOAD_KEY_ALIAS`,
`ARIES_UPLOAD_KEY_PASSWORD`, or from a properties file
(`storeFile`/`storePassword`/`keyAlias`/`keyPassword`) named by
`ARIES_UPLOAD_KEYSTORE_PROPERTIES`; without one the release APK is unsigned:

```sh
scripts/build-release.sh v0.1.0-rc.1 dist/      # needs cargo-ndk and cargo-about
```

Release builds need `cargo install cargo-about --locked --features cli`.

The release also builds the Beacon (linux and macOS, x86_64 and aarch64) and
publishes it with `beacon-SHA256SUMS`. The app pins those exact binaries:
`scripts/beacon-manifest.sh` writes `beacon-release.json`, and building the
core with `ARIES_BEACON_MANIFEST=<absolute path>` embeds it
(`crates/core/build.rs`). Without it a build pins no Beacon. The tag's
`X.Y.Z` must equal the workspace version in `Cargo.toml`. Locally (Linux
targets only):

```sh
scripts/build-beacon.sh dist/beacon             # static musl binaries
ARIES_BEACON_PARTIAL=1 scripts/beacon-manifest.sh v0.1.0 dist/beacon
ARIES_BEACON_MANIFEST=$PWD/dist/beacon/beacon-release.json scripts/build-release.sh v0.1.0 dist/
```

## License

Code is Apache-2.0 (`LICENSE`, `NOTICE`). The logo set in `assets/aries/` is
excluded from it and carries its own license (`assets/aries/LICENSE`); so do
icons generated from it. Third-party notices are generated for release builds:
Rust crates by cargo-about (`licenses/cargo-about.toml`, shipped as
`assets/licenses/rust-crates.txt`), Android dependencies by licensee (allowed
licenses in `android/app/build.gradle.kts`, shipped as
`assets/app/cash/licensee/artifacts.json`). A dependency under a license
outside those lists fails the build. Do not copy code from whip or aries-legacy
(AGPL). The herdr-core client is written from herdr-core's public protocol docs
(Apache-2.0); Aries does not depend on aries-legacy's MIT `herdr-protocol`
crate, because it also carries the legacy Herdr 1 protocol Aries does not
support and the part Aries needs is small.
