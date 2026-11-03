//! Seam 1: the public key as a QR code that any scanner reads back.

mod common;

use aries_core::QrMatrix;
use common::core_in;

/// Renders `qr` with a 4-module quiet zone and decodes it with an
/// independent QR reader.
fn decode(qr: &QrMatrix) -> String {
    let (size, scale, quiet) = (qr.size as usize, 4, 4);
    let px = (size + 2 * quiet) * scale;
    let mut img = rqrr::PreparedImage::prepare_from_greyscale(px, px, |x, y| {
        let (mx, my) = (x / scale, y / scale);
        let inside = (quiet..quiet + size).contains(&mx) && (quiet..quiet + size).contains(&my);
        if inside && qr.modules[(my - quiet) * size + (mx - quiet)] {
            0
        } else {
            255
        }
    });
    let grids = img.detect_grids();
    assert_eq!(grids.len(), 1, "one QR code");
    grids[0].decode().expect("decodes").1
}

#[test]
fn has_no_qr_code_before_a_key_exists() {
    let data = tempfile::tempdir().unwrap();
    assert_eq!(core_in(data.path()).public_key_qr().unwrap(), None);
}

#[test]
fn encodes_the_openssh_public_key_line() {
    let data = tempfile::tempdir().unwrap();
    let core = core_in(data.path());
    let key = core.ensure_key().unwrap();

    let qr = core.public_key_qr().unwrap().expect("a QR code");
    assert_eq!(qr.modules.len(), (qr.size * qr.size) as usize);
    assert_eq!(decode(&qr), key.openssh);
}
