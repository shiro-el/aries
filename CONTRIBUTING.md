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
| `fixtures/` | Test fixtures (`fixtures/sshd/Dockerfile`). |
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
cargo test -p aries-core -- --ignored              # SSH + password bootstrap tests against a docker sshd
cd android && ./gradlew :app:testDebugUnitTest      # Compose UI tests against a fake core (Robolectric)
cd android && ./gradlew :app:assembleDebug          # debug APK (builds the core with cargo-ndk)
cd android && ./gradlew :app:connectedDebugAndroidTest   # device tests (Android Keystore); needs a device/emulator
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

## License

Code is Apache-2.0 (`LICENSE`). The logo set is excluded from it and carries
its own license file when added. Do not copy code from whip or aries-legacy
(AGPL). The herdr-core client is written from herdr-core's public protocol docs
(Apache-2.0); Aries does not depend on aries-legacy's MIT `herdr-protocol`
crate, because it also carries the legacy Herdr 1 protocol Aries does not
support and the part Aries needs is small.
