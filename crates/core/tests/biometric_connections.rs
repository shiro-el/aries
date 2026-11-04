//! D30: overlapping public connection requests share one authorization, and
//! the next operation authenticates again after the final lease is released.
mod common;
use aries_core::{AriesCore, Endpoint, KeyProtection, KeyVault, VaultError};
use aries_test_support::HcSession;
use common::TestVault;
use russh::{Channel, ChannelId, server};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
};

struct Vault(AtomicUsize);
impl KeyVault for Vault {
    fn seal(&self, data: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        TestVault.seal(data, protection)
    }
    fn open(&self, data: Vec<u8>, protection: KeyProtection) -> Result<Vec<u8>, VaultError> {
        self.0.fetch_add(1, Ordering::SeqCst);
        TestVault.open(data, protection)
    }
}
struct Peer {
    barrier: Arc<tokio::sync::Barrier>,
    auths: Arc<AtomicUsize>,
    argv: Vec<String>,
    env: HashMap<String, String>,
    channels: HashMap<ChannelId, Channel<server::Msg>>,
    pumps: tokio::task::JoinSet<()>,
}
impl server::Handler for Peer {
    type Error = russh::Error;
    async fn auth_publickey(
        &mut self,
        _: &str,
        _: server::VerifiedPublicKey<'_>,
    ) -> Result<server::Auth, Self::Error> {
        // Hold each signing operation's lease until all three reach authentication.
        // A fourth, later connection is independent and must authenticate afresh.
        if self.auths.fetch_add(1, Ordering::SeqCst) < 3 {
            self.barrier.wait().await;
        }
        Ok(server::Auth::Accept)
    }
    async fn channel_open_session(
        &mut self,
        channel: Channel<server::Msg>,
        reply: server::ChannelOpenHandle,
        _: &mut server::Session,
    ) -> Result<(), Self::Error> {
        self.channels.insert(channel.id(), channel);
        reply.accept().await;
        Ok(())
    }
    async fn exec_request(
        &mut self,
        id: ChannelId,
        _: &[u8],
        session: &mut server::Session,
    ) -> Result<(), Self::Error> {
        session.channel_success(id)?;
        let mut stream = self.channels.remove(&id).unwrap().into_stream();
        let mut cmd = tokio::process::Command::new(&self.argv[0]);
        cmd.args(&self.argv[1..])
            .env_remove("HC_SOCKET")
            .env_remove("HC_SESSION")
            .envs(&self.env)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true);
        let mut child = cmd.spawn().unwrap();
        let mut input = child.stdin.take().unwrap();
        let mut output = child.stdout.take().unwrap();
        self.pumps.spawn(async move {
            let (mut read, mut write) = tokio::io::split(&mut stream);
            tokio::select! {
                _ = tokio::io::copy(&mut read, &mut input) => {},
                _ = tokio::io::copy(&mut output, &mut write) => {},
            }
            let _ = child.kill().await;
        });
        Ok(())
    }
}

#[tokio::test]
async fn three_connections_share_one_prompt_then_the_key_locks_again() {
    let hc = HcSession::start();
    let server_key =
        russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519).unwrap();
    let fingerprint = server_key
        .public_key()
        .fingerprint(russh::keys::HashAlg::Sha256)
        .to_string();
    let config = Arc::new(server::Config {
        keys: vec![server_key],
        ..Default::default()
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let argv = hc.connect_argv();
    let env = hc.env().into_iter().collect::<HashMap<_, _>>();
    let barrier = Arc::new(tokio::sync::Barrier::new(3));
    let auths = Arc::new(AtomicUsize::new(0));
    let server = tokio::spawn(async move {
        let mut peers = tokio::task::JoinSet::new();
        loop {
            let (tcp, _) = listener.accept().await.unwrap();
            let peer = Peer {
                barrier: barrier.clone(),
                auths: auths.clone(),
                argv: argv.clone(),
                env: env.clone(),
                channels: HashMap::new(),
                pumps: tokio::task::JoinSet::new(),
            };
            let config = config.clone();
            peers.spawn(async move {
                if let Ok(session) = server::run_stream(config, tcp, peer).await {
                    let _ = session.await;
                }
            });
        }
    });
    let dir = tempfile::tempdir().unwrap();
    let vault = Arc::new(Vault(AtomicUsize::new(0)));
    let core = AriesCore::new(dir.path().to_string_lossy().into(), vault.clone()).unwrap();
    core.ensure_key().unwrap();
    core.set_key_protection(KeyProtection::Biometric).unwrap();
    let host = core
        .add_host(
            "desk".into(),
            Endpoint::Ssh {
                address: "127.0.0.1".into(),
                port,
                user: "me".into(),
            },
        )
        .unwrap();
    core.trust_host_key(host.id.clone(), fingerprint).unwrap();
    vault.0.store(0, Ordering::SeqCst);
    let (a, b, c) = tokio::time::timeout(std::time::Duration::from_secs(15), async {
        tokio::join!(
            core.connect(host.id.clone()),
            core.connect(host.id.clone()),
            core.connect(host.id.clone())
        )
    })
    .await
    .unwrap();
    let connected = [a.unwrap(), b.unwrap(), c.unwrap()];
    for conn in &connected {
        assert!(conn.terminals().await.unwrap().is_empty());
    }
    assert_eq!(
        vault.0.load(Ordering::SeqCst),
        1,
        "one prompt for three overlapping authentications"
    );
    core.connect(host.id).await.unwrap().disconnect().await;
    assert_eq!(
        vault.0.load(Ordering::SeqCst),
        2,
        "no time-based authorization cache"
    );
    for conn in connected {
        conn.disconnect().await;
    }
    server.abort();
    let _ = server.await;
}
