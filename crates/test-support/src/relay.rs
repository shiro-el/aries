//! A fake Relay: a local HTTP endpoint speaking the Relay's API
//! (`relay/src/index.ts`) that records what reaches it instead of calling
//! FCM. Plus a minimal plain-HTTP client for tests that play the phone.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Everything the fake Relay has seen.
#[derive(Debug, Default, Clone)]
pub struct RelayLog {
    /// Live pairings: token -> device push token.
    pub pairings: HashMap<String, String>,
    /// Tokens deleted with `DELETE /v1/pairing`, in order (also unknown ones).
    pub deleted: Vec<String>,
    /// Envelopes posted to `/v1/push` with a live token: (token, body).
    pub pushes: Vec<(String, Vec<u8>)>,
    /// `PUT /v1/pairing` updates: (token, new device token).
    pub updates: Vec<(String, String)>,
}

pub struct FakeRelay {
    url: String,
    log: Arc<Mutex<RelayLog>>,
}

impl FakeRelay {
    /// Listens on `127.0.0.1`.
    pub fn start() -> Self {
        Self::start_on("127.0.0.1")
    }

    /// Listens on `ip` (e.g. the docker bridge gateway, so a container can
    /// reach it), on a random port.
    pub fn start_on(ip: &str) -> Self {
        let listener = TcpListener::bind((ip, 0)).expect("bind fake relay");
        let url = format!("http://{}", listener.local_addr().unwrap());
        let log = Arc::new(Mutex::new(RelayLog::default()));
        let shared = log.clone();
        std::thread::spawn(move || {
            let mut next = 0u32;
            for stream in listener.incoming() {
                let Ok(stream) = stream else { continue };
                next += 1;
                let log = shared.clone();
                let n = next;
                std::thread::spawn(move || {
                    let _ = serve(stream, &log, n);
                });
            }
        });
        Self { url, log }
    }

    /// Base URL, `http://ip:port` (no trailing slash).
    pub fn url(&self) -> &str {
        &self.url
    }

    pub fn log(&self) -> RelayLog {
        self.log.lock().unwrap().clone()
    }

    /// Waits until at least `n` envelopes arrived; returns them all.
    pub fn wait_pushes(&self, n: usize, timeout: Duration) -> Vec<(String, Vec<u8>)> {
        let deadline = Instant::now() + timeout;
        loop {
            let pushes = self.log().pushes;
            if pushes.len() >= n {
                return pushes;
            }
            assert!(
                Instant::now() < deadline,
                "fake relay got {} of {n} pushes",
                pushes.len()
            );
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}

struct Request {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

fn read_request(stream: &TcpStream) -> std::io::Result<Request> {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    reader.read_line(&mut line)?;
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or_default().to_owned();
    let path = parts.next().unwrap_or_default().to_owned();
    let mut headers = HashMap::new();
    loop {
        let mut h = String::new();
        reader.read_line(&mut h)?;
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_owned());
        }
    }
    let len: usize = headers
        .get("content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let mut body = vec![0; len];
    reader.read_exact(&mut body)?;
    Ok(Request {
        method,
        path,
        headers,
        body,
    })
}

fn serve(mut stream: TcpStream, log: &Mutex<RelayLog>, n: u32) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    let req = read_request(&stream)?;
    let bearer = req
        .headers
        .get("authorization")
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::to_owned);
    let mut log = log.lock().unwrap();
    let (status, body) = match (req.method.as_str(), req.path.as_str()) {
        ("POST", "/v1/pairings") => {
            let v: serde_json::Value = serde_json::from_slice(&req.body).unwrap_or_default();
            match v["device_token"].as_str() {
                Some(device) if v["platform"] == "fcm" => {
                    let token = format!("tok{n}-{}", std::process::id());
                    log.pairings.insert(token.clone(), device.to_owned());
                    (201, serde_json::json!({ "token": token }).to_string())
                }
                _ => (400, r#"{"error":"bad_request"}"#.to_owned()),
            }
        }
        ("PUT", "/v1/pairing") => match bearer.filter(|t| log.pairings.contains_key(t)) {
            Some(t) => {
                let v: serde_json::Value = serde_json::from_slice(&req.body).unwrap_or_default();
                let device = v["device_token"].as_str().unwrap_or_default().to_owned();
                log.pairings.insert(t.clone(), device.clone());
                log.updates.push((t, device));
                (204, String::new())
            }
            None => (401, r#"{"error":"unknown_pairing"}"#.to_owned()),
        },
        ("DELETE", "/v1/pairing") => {
            let t = bearer.unwrap_or_default();
            log.pairings.remove(&t);
            log.deleted.push(t);
            (204, String::new())
        }
        ("POST", "/v1/push") => match bearer.filter(|t| log.pairings.contains_key(t)) {
            Some(t) => {
                log.pushes.push((t, req.body));
                (202, String::new())
            }
            None => (401, r#"{"error":"unknown_pairing"}"#.to_owned()),
        },
        _ => (404, r#"{"error":"not_found"}"#.to_owned()),
    };
    drop(log);
    write!(
        stream,
        "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    )?;
    stream.flush()
}

/// A plain `http://` request; returns (status, body). Enough to play the
/// phone's HTTP client against [`FakeRelay`].
pub fn http_request(
    method: &str,
    url: &str,
    headers: &[(String, String)],
    body: &[u8],
) -> std::io::Result<(u16, Vec<u8>)> {
    let rest = url
        .strip_prefix("http://")
        .ok_or_else(|| std::io::Error::other(format!("only http:// is supported: {url}")))?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let mut stream = TcpStream::connect(authority)?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    let mut head = format!(
        "{method} {path} HTTP/1.1\r\nHost: {authority}\r\nContent-Length: {}\r\nConnection: close\r\n",
        body.len()
    );
    for (k, v) in headers {
        head.push_str(&format!("{k}: {v}\r\n"));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes())?;
    stream.write_all(body)?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw)?;
    let split = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| std::io::Error::other("no HTTP header end"))?;
    let status = String::from_utf8_lossy(&raw[..split])
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| std::io::Error::other("no HTTP status"))?;
    Ok((status, raw[split + 4..].to_vec()))
}

/// The docker bridge gateway (`172.17.0.1` usually): the address on this
/// machine that containers on the default network reach.
pub fn docker_gateway() -> String {
    let out = std::process::Command::new("docker")
        .args([
            "network",
            "inspect",
            "bridge",
            "-f",
            "{{(index .IPAM.Config 0).Gateway}}",
        ])
        .output()
        .expect("docker network inspect");
    let ip = String::from_utf8_lossy(&out.stdout).trim().to_owned();
    assert!(!ip.is_empty(), "no docker bridge gateway");
    ip
}
