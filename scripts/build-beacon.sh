#!/bin/sh
# Builds release Beacon binaries into OUT_DIR as aries-beacon-<os>-<arch>:
#
#   scripts/build-beacon.sh dist/beacon                  # this OS's two targets
#   scripts/build-beacon.sh dist/beacon linux-x86_64     # just one
#
# Linux binaries are static (musl, including ring and bundled SQLite).
# Use scripts/beacon-build.Dockerfile for the C cross toolchains; see CONTRIBUTING.
# macOS targets are CI-only on Linux: they require Apple's SDK and linker
# on a macOS machine (including ad-hoc signing for Apple silicon). Then run
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
    linux-x86_64)
      triple=x86_64-unknown-linux-musl
      export CC_x86_64_unknown_linux_musl=${CC_x86_64_unknown_linux_musl:-x86_64-linux-musl-gcc}
      cc=$CC_x86_64_unknown_linux_musl ;;
    linux-aarch64)
      triple=aarch64-unknown-linux-musl
      export CC_aarch64_unknown_linux_musl=${CC_aarch64_unknown_linux_musl:-aarch64-linux-musl-gcc}
      cc=$CC_aarch64_unknown_linux_musl ;;
    macos-x86_64) triple=x86_64-apple-darwin ;;
    macos-aarch64) triple=aarch64-apple-darwin ;;
    *) echo "build-beacon: unknown target $target" >&2; exit 1 ;;
  esac
  case $target in
    macos-*)
      if [ "$(uname -s)" != Darwin ]; then
        echo 'build-beacon: macOS targets require Apple SDK/linker; use macOS CI' >&2
        exit 1
      fi ;;
    linux-*)
      if ! command -v "$cc" >/dev/null 2>&1; then
        echo "build-beacon: missing musl C compiler $cc; use scripts/beacon-build.Dockerfile (CONTRIBUTING.md)" >&2
        exit 1
      fi ;;
  esac
  rustup target add "$triple"
  (cd "$root" &&
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld \
    CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld \
    cargo build --locked --release -p aries-beacon --target "$triple")
  # Honour Cargo's output directory when a container keeps build state outside
  # the mounted source tree. Relative paths are relative to the workspace.
  target_dir=${CARGO_TARGET_DIR:-$root/target}
  case $target_dir in /*) ;; *) target_dir=$root/$target_dir ;; esac
  cp "$target_dir/$triple/release/aries-beacon" "$out/aries-beacon-$target"
  # Smoke-run the binary this machine can execute.
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) native=linux-x86_64 ;; Linux-aarch64) native=linux-aarch64 ;;
    Darwin-x86_64) native=macos-x86_64 ;; Darwin-arm64) native=macos-aarch64 ;;
    *) native= ;;
  esac
  [ "$target" != "$native" ] || "$out/aries-beacon-$target" --version
  echo "build-beacon: $out/aries-beacon-$target"
done
