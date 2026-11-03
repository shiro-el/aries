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
cargo test -p aries-core --test ssh -- --ignored    # SSH tests against a docker sshd
cargo test -p aries-core --test beacon -- --ignored # Beacon install/remove on a docker systemd Host
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

## Beacon on a Host

`crates/core/src/beacon.rs` installs it over SSH: binary
`~/.local/bin/aries-beacon`, systemd user unit
`~/.config/systemd/user/aries-beacon.service`, private state (Call records)
in `~/.local/state/aries-beacon/`. Removal runs `UNINSTALL_STEPS` and keeps
the state directory. A ticket that puts something else on the Host (pairing,
managed skills) adds its own removal step there and tests it in
`crates/core/tests/beacon.rs`. The pinned release (`pinned_release`) and its
checksums come from the Beacon release pipeline; tests swap it with
`AriesCore::set_beacon_release`.

## License

Code is Apache-2.0 (`LICENSE`). The logo set is excluded from it and carries
its own license file when added. Do not copy code from whip or aries-legacy
(AGPL). The herdr-core client is written from herdr-core's public protocol docs
(Apache-2.0); Aries does not depend on aries-legacy's MIT `herdr-protocol`
crate, because it also carries the legacy Herdr 1 protocol Aries does not
support and the part Aries needs is small.
