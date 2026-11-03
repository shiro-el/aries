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

/// A Korean Host whose shell rejects locale-dependent machine commands.
/// Keep the tools directory alive for the duration of the session.
pub fn korean_host_with_c_locale_shell(extra: &[(&str, &str)]) -> (HcSession, tempfile::TempDir) {
    use std::os::unix::fs::PermissionsExt;
    let tools = tempfile::tempdir().unwrap();
    let shell = tools.path().join("sh");
    std::fs::write(
        &shell,
        "#!/bin/sh\n[ \"$LC_ALL\" = C ] && [ \"$LANG\" = C ] || { printf 'expected C locale' >&2; exit 9; }\nexec /bin/sh \"$@\"\n",
    )
    .unwrap();
    std::fs::set_permissions(&shell, std::fs::Permissions::from_mode(0o755)).unwrap();
    let path = format!(
        "{}:{}",
        tools.path().display(),
        std::env::var("PATH").unwrap()
    );
    let mut env = vec![
        ("PATH", path.as_str()),
        ("LANG", "ko_KR.UTF-8"),
        ("LC_ALL", "ko_KR.UTF-8"),
    ];
    env.extend_from_slice(extra);
    let hc = HcSession::start_with_env(&env);
    (hc, tools)
}

/// A Local endpoint that runs a shell script instead of herdr-core.
pub fn script_endpoint(script: &str) -> Endpoint {
    Endpoint::Local {
        argv: vec!["/bin/sh".into(), "-c".into(), script.into()],
        env: HashMap::new(),
    }
}
