#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::Arc;

use aries_core::{AriesCore, Endpoint, KeyProtection, KeyVault, VaultError};
use aries_test_support::HcSession;

/// Stands in for the Android Keystore: "seals" by XOR with a fixed pad and a
/// marker per protection, so tests can tell sealed bytes from plaintext and
/// bytes open only under the protection they were sealed with. Biometric
/// always succeeds here, as if the user authenticated.
pub struct TestVault;

fn mark(protection: KeyProtection) -> &'static [u8] {
    match protection {
        KeyProtection::Plain => b"sealed:",
        KeyProtection::Biometric => b"sealed-bio:",
    }
}

impl KeyVault for TestVault {
    fn seal(&self, plaintext: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        let mut out = mark(protection).to_vec();
        out.extend(plaintext.iter().map(|b| b ^ 0x5a));
        Ok(out)
    }

    fn open(&self, sealed: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        let body = sealed
            .strip_prefix(mark(protection))
            .ok_or(VaultError::Failed {
                detail: "not sealed by TestVault under this protection".into(),
            })?;
        Ok(body.iter().map(|b| b ^ 0x5a).collect())
    }
}

pub fn core_in(dir: &std::path::Path) -> Arc<AriesCore> {
    AriesCore::new(dir.to_string_lossy().into_owned(), Arc::new(TestVault)).expect("core")
}

/// A Local endpoint that runs `herdr-core connect` for `session`.
pub fn local_endpoint(session: &HcSession) -> Endpoint {
    Endpoint::Local {
        argv: session.connect_argv(),
        env: session.env().into_iter().collect::<HashMap<_, _>>(),
    }
}

/// A Local endpoint that runs a shell script instead of herdr-core.
pub fn script_endpoint(script: &str) -> Endpoint {
    Endpoint::Local {
        argv: vec!["/bin/sh".into(), "-c".into(), script.into()],
        env: HashMap::new(),
    }
}
