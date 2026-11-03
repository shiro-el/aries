# Linux release toolchains, shared by local builds and release.yml.
# Build on an x86_64 Docker host; aarch64 C code is cross-compiled, not emulated.
FROM rust:1.99.1-bookworm

RUN dpkg --add-architecture arm64 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        musl-tools musl-dev:arm64 gcc-aarch64-linux-gnu \
    && rm -rf /var/lib/apt/lists/*

# Debian supplies musl's aarch64 headers, libraries and GCC specs. Use those
# specs with the cross GCC so neither ring nor SQLite includes glibc headers.
RUN printf '%s\n' '#!/bin/sh' \
      'exec aarch64-linux-gnu-gcc -specs=/usr/lib/aarch64-linux-musl/musl-gcc.specs "$@"' \
      > /usr/local/bin/aarch64-linux-musl-gcc \
    && chmod +x /usr/local/bin/aarch64-linux-musl-gcc \
    && rustup target add x86_64-unknown-linux-musl aarch64-unknown-linux-musl

ENV AR_aarch64_unknown_linux_musl=aarch64-linux-gnu-ar
WORKDIR /work
