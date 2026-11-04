# Local ssh-key patch

Source: RustCrypto `ssh-key` 0.7.0-rc.11 from crates.io, checksum
`f9a32fae177b74a22aa9c5b01bf7e68b33545be32d9e381e248058d2adc15ce3`.
The source, upstream tests, manifest, README and both licenses are preserved.
This directory is excluded from the Aries workspace.

The only Rust change is in `src/private/ecdsa.rs`: accept nonempty private
scalars shorter than 32 bytes. OpenSSH encodes scalars as signed big-endian
mpints and removes leading zero bytes; a valid P-256 scalar can therefore be
31 bytes (or shorter). The decoder already pads short scalars to the curve's
fixed width. The upper bound and existing sign-prefix handling remain intact;
empty scalar encodings are still rejected.

Aries regression coverage lives at its public import API in
`crates/core/tests/key_import.rs`, using a disposable OpenSSH-generated key
that reproduced ticket 167, both plain and encrypted, and checking its public
identity after restart. Remove the patch once a compatible upstream release
accepts short scalars and that regression passes against it.
