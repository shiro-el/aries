//! One-time password login that appends the app's public key to the Host's
//! `~/.ssh/authorized_keys`. The password lives only for the call.

use russh::ChannelMsg;
use russh::client::KeyboardInteractiveAuthResponse;

use super::ssh::{handshake, unreachable_err};
use crate::KeyInstall;
use crate::error::CoreError;

/// Rounds of keyboard-interactive prompts to answer before giving up.
const MAX_KBD_ROUNDS: usize = 4;
const ADDED: &str = "ARIES-KEY:added";
const PRESENT: &str = "ARIES-KEY:present";

pub(crate) struct PasswordLogin {
    pub address: String,
    pub port: u16,
    pub user: String,
    pub password: String,
    pub trusted: Option<String>,
}

impl PasswordLogin {
    /// Logs in with the password and installs `public_key` (an OpenSSH line)
    /// unless the same key is already authorized.
    pub async fn install_key(self, public_key: &str) -> Result<KeyInstall, CoreError> {
        let script = install_script(public_key)?;
        let mut session = handshake(&self.address, self.port, self.trusted.clone()).await?;
        if !self.authenticate(&mut session).await? {
            return Err(CoreError::PasswordRejected);
        }
        let mut channel = session
            .channel_open_session()
            .await
            .map_err(unreachable_err)?;
        // `sh -s` reads the script from stdin, so it runs the same under any
        // login shell (fish, csh) and needs no quoting.
        channel.exec(true, "sh -s").await.map_err(unreachable_err)?;
        channel
            .data(script.as_bytes())
            .await
            .map_err(unreachable_err)?;
        channel.eof().await.map_err(unreachable_err)?;

        let (mut stdout, mut stderr, mut status) = (Vec::new(), Vec::new(), None);
        while let Some(msg) = channel.wait().await {
            match msg {
                ChannelMsg::Data { data } => stdout.extend_from_slice(&data),
                ChannelMsg::ExtendedData { data, .. } => stderr.extend_from_slice(&data),
                ChannelMsg::ExitStatus { exit_status } => status = Some(exit_status),
                ChannelMsg::Close => break,
                _ => {}
            }
        }
        let _ = session
            .disconnect(russh::Disconnect::ByApplication, "", "")
            .await;
        outcome(&stdout, &stderr, status)
    }

    /// Tries `password`, then keyboard-interactive (PAM-style Hosts), with
    /// the same password.
    async fn authenticate(
        &self,
        session: &mut russh::client::Handle<super::ssh::Checker>,
    ) -> Result<bool, CoreError> {
        let auth = session
            .authenticate_password(self.user.clone(), self.password.clone())
            .await
            .map_err(unreachable_err)?;
        if auth.success() {
            return Ok(true);
        }
        let mut reply = session
            .authenticate_keyboard_interactive_start(self.user.clone(), None)
            .await
            .map_err(unreachable_err)?;
        for _ in 0..MAX_KBD_ROUNDS {
            match reply {
                KeyboardInteractiveAuthResponse::Success => return Ok(true),
                KeyboardInteractiveAuthResponse::Failure { .. } => return Ok(false),
                KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                    let answers = prompts.iter().map(|_| self.password.clone()).collect();
                    reply = session
                        .authenticate_keyboard_interactive_respond(answers)
                        .await
                        .map_err(unreachable_err)?;
                }
            }
        }
        Ok(false)
    }
}

/// Shell script that adds the key once. It matches on the base64 body, so
/// the same key with another comment counts as present.
fn install_script(public_key: &str) -> Result<String, CoreError> {
    let public_key = public_key.trim();
    let body = public_key.split_whitespace().nth(1).unwrap_or_default();
    let safe = |s: &str| {
        s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b" +/=-_.@".contains(&b))
    };
    if body.is_empty() || !safe(public_key) {
        return Err(CoreError::Storage {
            detail: "the app's public key is malformed".into(),
        });
    }
    Ok(format!(
        r#"umask 077
k='{public_key}'
b='{body}'
d="$HOME/.ssh"
f="$d/authorized_keys"
if [ -f "$f" ] && grep -qF "$b" "$f"; then echo {PRESENT}; exit 0; fi
mkdir -p "$d" || exit 3
chmod 700 "$d" || exit 3
touch "$f" || exit 3
chmod 600 "$f" || exit 3
if [ -s "$f" ] && [ -n "$(tail -c 1 "$f")" ]; then echo >> "$f" || exit 3; fi
printf '%s\n' "$k" >> "$f" || exit 3
echo {ADDED}
"#
    ))
}

fn outcome(stdout: &[u8], stderr: &[u8], status: Option<u32>) -> Result<KeyInstall, CoreError> {
    let out = String::from_utf8_lossy(stdout);
    // A login shell may print a banner first, so look for the marker
    // anywhere in the output.
    if status == Some(0) && out.contains(PRESENT) {
        return Ok(KeyInstall::AlreadyPresent);
    }
    if status == Some(0) && out.contains(ADDED) {
        return Ok(KeyInstall::Added);
    }
    let err = String::from_utf8_lossy(stderr).trim().to_owned();
    Err(CoreError::KeyInstallFailed {
        detail: if err.is_empty() {
            match status {
                Some(code) => format!("the install script exited with status {code}"),
                None => "the Host closed the session".into(),
            }
        } else {
            err
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::process::{Command, Stdio};

    const KEY: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGtleWtleWtleWtleWtleWtleWtleWtleWtleWtleWtl aries";

    /// Runs the install script with `$HOME` at `home`, as `sh -s` would on a Host.
    fn run(home: &std::path::Path) -> Result<KeyInstall, CoreError> {
        let mut child = Command::new("sh")
            .arg("-s")
            .env("HOME", home)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let script = install_script(KEY).unwrap();
        child
            .stdin
            .take()
            .unwrap()
            .write_all(script.as_bytes())
            .unwrap();
        let out = child.wait_with_output().unwrap();
        outcome(
            &out.stdout,
            &out.stderr,
            out.status.code().map(|c| c as u32),
        )
    }

    fn keys(home: &std::path::Path) -> String {
        std::fs::read_to_string(home.join(".ssh/authorized_keys")).unwrap()
    }

    #[test]
    fn appends_on_a_new_line_after_a_file_without_a_trailing_newline() {
        let home = tempfile::tempdir().unwrap();
        std::fs::create_dir(home.path().join(".ssh")).unwrap();
        std::fs::write(
            home.path().join(".ssh/authorized_keys"),
            "ssh-rsa AAAAB3other me",
        )
        .unwrap();

        assert_eq!(run(home.path()).unwrap(), KeyInstall::Added);
        assert_eq!(
            keys(home.path()),
            format!("ssh-rsa AAAAB3other me\n{KEY}\n")
        );
    }

    #[test]
    fn treats_the_same_key_with_another_comment_as_present() {
        let home = tempfile::tempdir().unwrap();
        std::fs::create_dir(home.path().join(".ssh")).unwrap();
        let line = KEY.replace(" aries", " phone@old");
        std::fs::write(
            home.path().join(".ssh/authorized_keys"),
            format!("{line}\n"),
        )
        .unwrap();

        assert_eq!(run(home.path()).unwrap(), KeyInstall::AlreadyPresent);
        assert_eq!(keys(home.path()), format!("{line}\n"));
    }

    #[test]
    fn creates_the_ssh_dir_with_private_modes() {
        use std::os::unix::fs::PermissionsExt;
        let home = tempfile::tempdir().unwrap();

        assert_eq!(run(home.path()).unwrap(), KeyInstall::Added);
        let mode = |p: &str| {
            std::fs::metadata(home.path().join(p))
                .unwrap()
                .permissions()
                .mode()
                & 0o777
        };
        assert_eq!(mode(".ssh"), 0o700);
        assert_eq!(mode(".ssh/authorized_keys"), 0o600);
        assert_eq!(keys(home.path()), format!("{KEY}\n"));
    }

    #[test]
    fn finds_the_marker_after_a_login_banner() {
        let out = format!("Welcome to the noisy Host\nlast login: never {ADDED}\n");
        assert_eq!(
            outcome(out.as_bytes(), b"", Some(0)).unwrap(),
            KeyInstall::Added
        );
    }

    #[test]
    fn refuses_a_key_that_would_break_the_shell_quoting() {
        assert!(install_script("ssh-ed25519 AAAA' ; rm -rf ~ '").is_err());
    }
}
