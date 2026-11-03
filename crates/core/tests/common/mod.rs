#![allow(dead_code)]

use std::collections::HashMap;
use std::sync::Arc;

use aries_core::{AriesCore, Endpoint, KeyVault, VaultError};
use aries_test_support::HcSession;

/// Stands in for the Android Keystore: "seals" by XOR with a fixed pad and a
/// marker so tests can tell sealed bytes from plaintext.
pub struct TestVault;

const MARK: &[u8] = b"sealed:";

impl KeyVault for TestVault {
    fn seal(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, VaultError> {
        let mut out = MARK.to_vec();
        out.extend(plaintext.iter().map(|b| b ^ 0x5a));
        Ok(out)
    }

    fn open(&self, sealed: Vec<u8>) -> Result<Vec<u8>, VaultError> {
        let body = sealed.strip_prefix(MARK).ok_or(VaultError::Failed {
            detail: "not sealed by TestVault".into(),
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
