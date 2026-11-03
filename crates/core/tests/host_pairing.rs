mod common;

#[test]
fn scanning_an_invalid_or_expired_qr_never_registers_a_host() {
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    assert!(core.inspect_pairing_qr("not a pairing QR".into()).is_err());
    assert!(core.hosts().is_empty());
}

// Real CLI + encrypted pairing + real sshd. No pre-authorized phone key.
#[tokio::test]
#[ignore = "needs docker"]
async fn qr_registers_one_phone_and_pins_the_host_without_password_bootstrap() {
    use aries_core::{CoreError, Endpoint};
    use aries_protocol::host_pairing::PairingOffer;
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    fn docker(args: &[&str]) -> String {
        let output = Command::new("docker").args(args).output().unwrap();
        assert!(
            output.status.success(),
            "docker setup failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8(output.stdout).unwrap().trim().to_owned()
    }
    struct Container(String);
    impl Drop for Container {
        fn drop(&mut self) {
            let _ = Command::new("docker")
                .args(["rm", "-f", &self.0])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        }
    }
    let image = "aries-mvp-116-qr-host";
    let fixture = aries_test_support::repo_root().join("fixtures/sshd-systemd");
    docker(&["build", "-q", "-t", image, fixture.to_str().unwrap()]);
    let name = format!("aries-mvp-116-pair-{}", std::process::id());
    let beacon = aries_test_support::beacon_bin();
    let mount = format!("{}:/usr/local/bin/aries-beacon:ro", beacon.display());
    let hc = format!(
        "{}:/usr/local/bin/herdr-core:ro",
        aries_test_support::herdr_core_bin().display()
    );
    docker(&[
        "run",
        "-d",
        "--rm",
        "--name",
        &name,
        "-p",
        "127.0.0.1::22",
        "-p",
        "127.0.0.1::2223",
        "-v",
        &mount,
        "-v",
        &hc,
        "--entrypoint",
        "/bin/sh",
        image,
        "-c",
        "mkdir -p /run/sshd && exec /usr/sbin/sshd -D -e",
    ]);
    let container = Container(name);
    docker(&[
        "exec",
        "-u",
        "aries",
        &container.0,
        "sh",
        "-c",
        "printf '# retained entry' > /home/aries/.ssh/authorized_keys",
    ]);
    let mapped = |port| {
        docker(&["port", &container.0, port])
            .rsplit(':')
            .next()
            .unwrap()
            .parse::<u16>()
            .unwrap()
    };
    let port = mapped("22/tcp");
    let pair_port = mapped("2223/tcp");
    let mut child = Command::new("docker")
        .args([
            "exec",
            "-u",
            "aries",
            "-e",
            "HOME=/home/aries",
            &container.0,
            "aries-beacon",
            "pair-qr",
            "--address",
            "127.0.0.1",
            "--port",
            &port.to_string(),
            "--listen",
            "0.0.0.0:2223",
            "--pair-port",
            &pair_port.to_string(),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut output = BufReader::new(child.stdout.take().unwrap());
    let mut rendered = String::new();
    let qr = loop {
        let mut line = String::new();
        assert!(
            output.read_line(&mut line).unwrap() > 0,
            "Beacon did not produce a QR"
        );
        if line.starts_with("aries-pair:") {
            break line.trim().to_owned();
        }
        rendered.push_str(&line);
    };
    // Decode the actual terminal QR, including its quiet zone. Explicit
    // black-on-white ANSI colors keep it scannable in dark terminals too.
    assert!(rendered.contains("\x1b[30;47m"));
    let rendered = rendered.replace("\x1b[30;47m", "").replace("\x1b[0m", "");
    let rows: Vec<Vec<char>> = rendered
        .lines()
        .filter(|line| !line.is_empty())
        .map(|line| line.chars().collect())
        .collect();
    let size = rows.len();
    assert!(rows.iter().all(|row| row.len() == size * 2));
    let mut image = rqrr::PreparedImage::prepare_from_greyscale(size * 4, size * 4, |x, y| {
        if rows[y / 4][(x / 4) * 2] == '█' {
            0
        } else {
            255
        }
    });
    let grids = image.detect_grids();
    assert_eq!(grids.len(), 1);
    let (_, decoded) = grids[0].decode().unwrap();
    assert!(
        decoded == qr,
        "terminal QR did not encode the advertised offer"
    );
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let offer = PairingOffer::from_qr(&qr, now).unwrap();
    let data = tempfile::tempdir().unwrap();
    let core = common::core_in(data.path());
    let public = core.ensure_key().unwrap();
    let details = core.inspect_pairing_qr(qr.clone()).unwrap();
    assert_eq!(details.port, port);
    assert_eq!(details.user, "aries");

    // Malformed, expired, substituted normal/pairing pins and wrong tokens
    // never add a Host and never consume the valid offer.
    let mut invalid = offer.clone();
    invalid.expires_at = now;
    assert!(matches!(
        core.pair_host_qr("dev".into(), invalid.to_qr().unwrap())
            .await,
        Err(CoreError::InvalidPairingQr)
    ));
    invalid = offer.clone();
    invalid.host_key = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".into();
    assert!(matches!(
        core.pair_host_qr("dev".into(), invalid.to_qr().unwrap())
            .await,
        Err(CoreError::HostKeyChanged { .. })
    ));
    invalid = offer.clone();
    invalid.pair_key = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".into();
    assert!(matches!(
        core.pair_host_qr("dev".into(), invalid.to_qr().unwrap())
            .await,
        Err(CoreError::HostKeyChanged { .. })
    ));
    invalid = offer.clone();
    invalid.token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".into();
    assert!(matches!(
        core.pair_host_qr("dev".into(), invalid.to_qr().unwrap())
            .await,
        Err(CoreError::PairingRefused)
    ));
    assert!(core.hosts().is_empty());

    let second_data = tempfile::tempdir().unwrap();
    let second = common::core_in(second_data.path());
    second.ensure_key().unwrap();
    let (a, b) = tokio::join!(
        core.pair_host_qr("dev".into(), qr.clone()),
        second.pair_host_qr("other".into(), qr.clone())
    );
    assert_eq!(
        usize::from(a.is_ok()) + usize::from(b.is_ok()),
        1,
        "exactly one phone can consume the token"
    );
    let (winner, host, winner_public) = if let Ok(host) = a {
        (core.clone(), host, public.openssh)
    } else {
        (
            second.clone(),
            b.unwrap(),
            second.public_key().unwrap().unwrap().openssh,
        )
    };
    assert_eq!(
        host.endpoint,
        Endpoint::Ssh {
            address: "127.0.0.1".into(),
            port,
            user: "aries".into()
        }
    );
    assert_eq!(host.host_key.as_deref(), Some(details.host_key.as_str()));
    assert!(matches!(
        winner.pair_host_qr("replay".into(), qr.clone()).await,
        Err(CoreError::PairingRefused)
    ));
    assert_eq!(winner.hosts().len(), 1);
    let authorized = docker(&[
        "exec",
        &container.0,
        "cat",
        "/home/aries/.ssh/authorized_keys",
    ]);
    assert_eq!(
        authorized.lines().collect::<Vec<_>>(),
        vec!["# retained entry".to_owned(), winner_public]
    );
    winner.connect(host.id).await.unwrap().disconnect().await;
    // Destroy only this test's container, then reap its docker-exec client.
    drop(container);
    let _ = child.wait();
}
