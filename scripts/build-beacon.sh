#!/bin/sh
# Builds release Beacon binaries into OUT_DIR as aries-beacon-<os>-<arch>:
#
#   scripts/build-beacon.sh dist/beacon                  # this OS's two targets
#   scripts/build-beacon.sh dist/beacon linux-x86_64     # just one
#
# Linux binaries are static (musl, linked with the toolchain's rust-lld) so
# they run on any distribution; macOS binaries need a macOS machine (the
# linker ad-hoc signs them, which Apple silicon requires). Then run
# scripts/beacon-manifest.sh to pin them.
set -eu

out=${1:?usage: build-beacon.sh OUT_DIR [os-arch...]}
shift
root=$(cd "$(dirname "$0")/.." && pwd)

if [ $# -eq 0 ]; then
  case $(uname -s) in
    Linux) set -- linux-x86_64 linux-aarch64 ;;
    Darwin) set -- macos-x86_64 macos-aarch64 ;;
    *) echo "build-beacon: no targets for $(uname -s)" >&2; exit 1 ;;
  esac
fi

mkdir -p "$out"
for target in "$@"; do
  case $target in
    linux-x86_64) triple=x86_64-unknown-linux-musl ;;
    linux-aarch64) triple=aarch64-unknown-linux-musl ;;
    macos-x86_64) triple=x86_64-apple-darwin ;;
    macos-aarch64) triple=aarch64-apple-darwin ;;
    *) echo "build-beacon: unknown target $target" >&2; exit 1 ;;
  esac
  rustup target add "$triple" >/dev/null 2>&1 || true
  (cd "$root" &&
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld \
    CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld \
    cargo build --locked --release -p aries-beacon --target "$triple")
  cp "$root/target/$triple/release/aries-beacon" "$out/aries-beacon-$target"
  # Smoke-run the binary this machine can execute.
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) native=linux-x86_64 ;; Linux-aarch64) native=linux-aarch64 ;;
    Darwin-x86_64) native=macos-x86_64 ;; Darwin-arm64) native=macos-aarch64 ;;
    *) native= ;;
  esac
  [ "$target" != "$native" ] || "$out/aries-beacon-$target" --version
  echo "build-beacon: $out/aries-beacon-$target"
done
