//! Ephemeral, QR-pinned SSH service. It accepts only one operation: append
//! one valid public key to this user's authorized_keys. No shell/forwarding.
use std::fs::{self, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use aries_protocol::host_pairing::{INSTALL_COMMAND, LIFETIME_SECS, PairingOffer};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::RngExt;
use russh::keys::{Algorithm, HashAlg, PrivateKey, PublicKey};
use russh::server::{self, Session};
use russh::{Channel, ChannelId};
use subtle::ConstantTimeEq;
use tokio::net::TcpListener;

pub fn command(args: &[String]) -> Result<(), String> {
    tokio::runtime::Runtime::new()
        .map_err(|e| e.to_string())?
        .block_on(run(args))
}

async fn run(args: &[String]) -> Result<(), String> {
    let mut address = None;
    let mut port = 22;
    let mut listen = "0.0.0.0:0".to_owned();
    let mut pair_port = None;
    let mut host_key = "/etc/ssh/ssh_host_ed25519_key.pub".to_owned();
    let mut json = false;
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        if arg == "--json" {
            json = true;
            continue;
        }
        let value = args.next().ok_or("missing pair-qr option value")?;
        match arg.as_str() {
            "--address" => address = Some(value.clone()),
            "--port" => port = value.parse().map_err(|_| "invalid SSH port")?,
            "--listen" => listen = value.clone(),
            "--pair-port" => {
                pair_port = Some(value.parse::<u16>().map_err(|_| "invalid pairing port")?)
            }
            "--host-key" => host_key = value.clone(),
            _ => return Err("unknown pair-qr option".into()),
        }
    }
    let address = address.ok_or("usage: aries-beacon pair-qr --address ADDRESS [--port PORT] [--listen IP:PORT] [--pair-port ADVERTISED_PORT] [--host-key SSH_PUBLIC_KEY_FILE] [--json]")?;
    let user = std::process::Command::new("id")
        .arg("-un")
        .output()
        .map_err(|e| e.to_string())?;
    if !user.status.success() {
        return Err("could not identify the current user".into());
    }
    let user = String::from_utf8(user.stdout)
        .map_err(|_| "invalid user name")?
        .trim()
        .to_owned();
    let public = fs::read_to_string(host_key).map_err(|e| format!("read sshd public key: {e}"))?;
    let host_key = PublicKey::from_openssh(public.trim())
        .map_err(|_| "invalid sshd public key")?
        .fingerprint(HashAlg::Sha256)
        .to_string();
    let key =
        PrivateKey::random(&mut rand::rng(), Algorithm::Ed25519).map_err(|e| e.to_string())?;
    let listener = TcpListener::bind(&listen)
        .await
        .map_err(|e| format!("pairing listen: {e}"))?;
    let token: [u8; 32] = rand::rng().random();
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_secs();
    let offer = PairingOffer {
        version: 1,
        address,
        port,
        user,
        host_key,
        pair_port: pair_port.unwrap_or(listener.local_addr().map_err(|e| e.to_string())?.port()),
        pair_key: key.public_key().fingerprint(HashAlg::Sha256).to_string(),
        token: URL_SAFE_NO_PAD.encode(token),
        expires_at: now + LIFETIME_SECS,
    };
    offer.validate(now).map_err(|e| e.to_string())?;
    let until = Instant::now() + Duration::from_secs(LIFETIME_SECS);
    let qr = offer.to_qr().map_err(|e| e.to_string())?;
    if !json {
        eprintln!(
            "Scan in Aries to register {}@{}:{}. Expires in 5 minutes; one phone only. Keep this QR private. Pairing listener: {}",
            offer.user,
            offer.address,
            offer.port,
            listener.local_addr().map_err(|e| e.to_string())?
        );
        let code = qrcode::QrCode::new(qr.as_bytes()).map_err(|e| e.to_string())?;
        println!(
            "\x1b[30;47m{}\x1b[0m",
            code.render::<char>()
                .quiet_zone(true)
                .module_dimensions(2, 1)
                .dark_color('█')
                .light_color(' ')
                .build()
        );
    }
    println!("{qr}");
    std::io::stdout().flush().map_err(|e| e.to_string())?;
    let state = Arc::new(Mutex::new(State {
        offer,
        until,
        used: false,
    }));
    let config = Arc::new(server::Config {
        keys: vec![key],
        inactivity_timeout: Some(Duration::from_secs(20)),
        max_auth_attempts: 3,
        ..Default::default()
    });
    // Dropping the JoinSet aborts every accepted connection, including those
    // which authenticated before the token was consumed or the deadline passed.
    let mut sessions = tokio::task::JoinSet::new();
    let deadline = tokio::time::sleep_until(until.into());
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            _ = &mut deadline => return Ok(()),
            Some(_) = sessions.join_next(), if !sessions.is_empty() => {},
            accepted = listener.accept(), if sessions.len() < 16 => {
                let (stream, _) = accepted.map_err(|e| e.to_string())?;
                let handler = Handler { state: state.clone(), authenticated: false, channel: None, data: Vec::new(), install: false };
                let config = config.clone();
                sessions.spawn(async move {
                    if let Ok(session) = server::run_stream(config, stream, handler).await { let _ = session.await; }
                });
            }
        }
    }
}

struct State {
    offer: PairingOffer,
    until: Instant,
    used: bool,
}
impl State {
    fn available(&self) -> bool {
        !self.used
            && Instant::now() < self.until
            && SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .is_ok_and(|now| now.as_secs() < self.offer.expires_at)
    }
}

struct Handler {
    state: Arc<Mutex<State>>,
    authenticated: bool,
    channel: Option<ChannelId>,
    data: Vec<u8>,
    install: bool,
}

impl server::Handler for Handler {
    type Error = russh::Error;

    async fn auth_password(
        &mut self,
        user: &str,
        password: &str,
    ) -> Result<server::Auth, Self::Error> {
        let state = self.state.lock().expect("pairing state");
        self.authenticated = state.available()
            && user == state.offer.user
            && bool::from(password.as_bytes().ct_eq(state.offer.token.as_bytes()));
        Ok(if self.authenticated {
            server::Auth::Accept
        } else {
            server::Auth::reject()
        })
    }

    async fn channel_open_session(
        &mut self,
        channel: Channel<server::Msg>,
        reply: server::ChannelOpenHandle,
        _: &mut Session,
    ) -> Result<(), Self::Error> {
        if self.authenticated && self.channel.is_none() {
            self.channel = Some(channel.id());
            reply.accept().await;
        }
        Ok(())
    }

    async fn exec_request(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        if self.channel == Some(channel) && data == INSTALL_COMMAND && !self.install {
            self.install = true;
            session.channel_success(channel)?;
        } else {
            session.channel_failure(channel)?;
        }
        Ok(())
    }

    async fn data(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        _: &mut Session,
    ) -> Result<(), Self::Error> {
        if self.channel != Some(channel) || !self.install || self.data.len() + data.len() > 8192 {
            return Err(russh::Error::Disconnect);
        }
        self.data.extend_from_slice(data);
        Ok(())
    }

    async fn channel_eof(
        &mut self,
        channel: ChannelId,
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        let mut state = self.state.lock().expect("pairing state");
        let result = if self.channel != Some(channel) || !self.install || !state.available() {
            Err("pairing expired or already used")
        } else {
            std::str::from_utf8(&self.data)
                .ok()
                .filter(|s| !s.chars().any(char::is_control))
                .and_then(|s| PublicKey::from_openssh(s).ok())
                .ok_or("invalid public key")
                .and_then(|key| {
                    let line = key.to_openssh().map_err(|_| "invalid public key")?;
                    // Consume before writing: a failed/ambiguous write needs a
                    // fresh QR, and can never authorize a second phone.
                    state.used = true;
                    append_key(&crate::home(), &line).map_err(|_| "could not install public key")
                })
        };
        let ok = result.is_ok();
        session.data(
            channel,
            if ok {
                b"ok\n".to_vec()
            } else {
                b"pairing refused\n".to_vec()
            },
        )?;
        session.exit_status_request(channel, if ok { 0 } else { 1 })?;
        session.eof(channel)?;
        session.close(channel)?;
        self.install = false;
        Ok(())
    }
}

fn append_key(home: &Path, key: &str) -> std::io::Result<()> {
    let dir = home.join(".ssh");
    match fs::symlink_metadata(&dir) {
        Ok(meta) if !meta.is_dir() || meta.file_type().is_symlink() => {
            return Err(std::io::Error::other("unsafe .ssh directory"));
        }
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => fs::create_dir(&dir)?,
        Err(e) => return Err(e),
    }
    fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
    let mut file = OpenOptions::new()
        .read(true)
        .append(true)
        .create(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(dir.join("authorized_keys"))?;
    if !file.metadata()?.is_file() {
        return Err(std::io::Error::other("unsafe authorized_keys"));
    }
    file.lock()?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    let mut old = String::new();
    file.read_to_string(&mut old)?;
    if !old.lines().any(|line| line == key) {
        file.seek(SeekFrom::End(0))?;
        if !old.is_empty() && !old.ends_with('\n') {
            file.write_all(b"\n")?;
        }
        writeln!(file, "{key}")?;
        file.sync_data()?;
    }
    Ok(())
}
