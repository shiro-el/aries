//! Attached Terminals. `terminal.attach` streams raw VT bytes; the core keeps
//! the VT state ([`vt::Vt`], `alacritty_terminal`) and hands the UI batches
//! of changed rows ([`ScreenFrame`]), never raw bytes or one call per cell.
//!
//! Batch unit: one [`ScreenFrame`] per [`TerminalView::next`] call, holding
//! every row that changed since the previous frame (whole rows, as styled
//! runs). Frames are at most one per [`FRAME_INTERVAL`]; output that arrives
//! in between is merged into the next frame. The pull also is the back
//! pressure: a UI that draws slowly gets fewer, larger frames, while the
//! stream is still read and credited at network speed.

mod keys;
mod vt;

pub use keys::{KeyMods, TerminalKey};

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Value, json};
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::JoinHandle;
use tokio::time::Instant;

use crate::error::{CoreError, protocol};
use crate::hc::{Frame, HcClient, KIND_CLOSE, KIND_CREDIT, KIND_DATA, KIND_RESIZE};
use vt::Vt;

/// Shortest time between two frames (60 Hz).
pub const FRAME_INTERVAL: Duration = Duration::from_millis(16);

/// How to attach to a Terminal.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AttachOptions {
    /// Watch without typing or resizing: the desktop keeps its size, and the
    /// grid follows the Terminal's own size.
    pub observe: bool,
    /// Grid to ask for. An observer gets the Terminal's grid instead.
    pub cols: u16,
    pub rows: u16,
    /// Scrollback lines the first paint should include.
    #[uniffi(default = 0)]
    pub history: u32,
    /// Coming back after a dropped line (network change, app resumed): the
    /// first paint is the whole screen as it is now, and an input viewer
    /// takes the size only once it is active again, so re-attaching does not
    /// resize the desktop.
    #[uniffi(default = false)]
    pub resumed: bool,
}

/// One row's cells of one style, left to right. Blank default cells between
/// runs are left out.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CellRun {
    /// First column.
    pub column: u16,
    /// Columns covered (a wide character covers two).
    pub width: u16,
    pub text: String,
    /// Every character here is double width (CJK, emoji).
    pub wide: bool,
    /// `0xRRGGBB`; `None` is the theme's default colour.
    pub fg: Option<u32>,
    pub bg: Option<u32>,
    pub bold: bool,
    pub dim: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikeout: bool,
    /// Swap fg and bg when drawing.
    pub inverse: bool,
    /// OSC 8 hyperlink target of these cells.
    #[uniffi(default = None)]
    pub link: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScreenLine {
    pub row: u16,
    /// A row with no runs is blank.
    pub runs: Vec<CellRun>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct CursorPos {
    pub row: u16,
    pub column: u16,
}

/// A search match's cells on one row of this frame's viewport.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TerminalSearchHighlight {
    pub row: u16,
    pub column: u16,
    pub width: u16,
    pub current: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TerminalSearch {
    /// Non-overlapping matches across the retained scrollback and live screen.
    pub total: u32,
    /// Zero-based selected match, oldest first; None when no matches exist.
    pub current: Option<u32>,
    /// Visible matches only, in the same coordinates as this frame's cells.
    pub highlights: Vec<TerminalSearchHighlight>,
}

/// A batch of screen changes.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScreenFrame {
    pub cols: u16,
    pub rows: u16,
    /// `lines` holds every row: start from a blank grid of `cols` x `rows`.
    /// Otherwise only changed rows are listed; keep the others.
    pub full: bool,
    pub lines: Vec<ScreenLine>,
    /// `None` while the program hides the cursor.
    pub cursor: Option<CursorPos>,
    pub title: Option<String>,
    /// Lines the view is scrolled back into the scrollback; 0 is the live
    /// screen. See [`TerminalView::scroll_to`].
    #[uniffi(default = 0)]
    pub scrolled: u32,
    /// Scrollback lines above the live screen.
    #[uniffi(default = 0)]
    pub history: u32,
    /// Search metadata is refreshed with the VT, even for a partial frame.
    #[uniffi(default = None)]
    pub search: Option<TerminalSearch>,
}

/// How the Terminal's program ended, from the stream's CLOSE.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TerminalExit {
    /// `exited`, `killed`, `server_stop`, ...
    pub reason: String,
    pub exit_code: Option<i32>,
    pub signal: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TerminalUpdate {
    Screen {
        frame: ScreenFrame,
    },
    /// The program ended; this is the last remote update. Local search/scroll
    /// operations can still queue screen frames until the view is detached.
    Exited {
        exit: TerminalExit,
    },
}

/// Counters for tests and latency measurements.
#[doc(hidden)]
#[derive(Debug, Clone, Copy, Default)]
pub struct ViewStats {
    /// DATA bytes received and fed to the VT.
    pub bytes: u64,
    /// Frames returned by `next`.
    pub frames: u64,
}

struct State {
    vt: Vt,
    dirty: bool,
    exit: Option<TerminalExit>,
    /// The stream is over; `next` still returns the last screen and `exit`.
    ended: bool,
    last_frame: Option<Instant>,
}

/// One attached Terminal: its screen, live.
#[derive(uniffi::Object)]
pub struct TerminalView {
    inner: Arc<Inner>,
    task: JoinHandle<()>,
}

struct Inner {
    client: Arc<HcClient>,
    channel: u32,
    state: Arc<Mutex<State>>,
    wake: Arc<Notify>,
    /// Serialises `next` callers.
    reading: tokio::sync::Mutex<()>,
    bytes: Arc<AtomicU64>,
    frames: AtomicU64,
    hold_credit: watch::Sender<bool>,
    detached: AtomicBool,
    /// Attached with `observe: true`: no typing, no resizing.
    observe: bool,
}

impl TerminalView {
    pub(crate) async fn attach(
        client: Arc<HcClient>,
        address: String,
        options: AttachOptions,
    ) -> Result<Self, CoreError> {
        let mut params = json!({
            "terminal": address,
            "cols": options.cols.max(1),
            "rows": options.rows.max(1),
            "observe": options.observe,
        });
        if options.observe {
            params["resize_notifications"] = json!(true);
        }
        if options.history > 0 {
            params["history"] = json!(options.history);
        }
        if options.resumed {
            params["resumed"] = json!(true);
        }
        let reply = client.call("terminal.attach", params).await?;
        let channel = reply["channel"]
            .as_u64()
            .ok_or_else(|| protocol("terminal.attach returned no channel"))?
            as u32;
        // Until a RESIZE says otherwise: the Terminal's size from the reply
        // (an observer's grid), else what we asked for.
        let dim = |k: &str, asked: u16| {
            reply["terminal"][k]
                .as_u64()
                .filter(|_| options.observe)
                .map(|v| v as u16)
                .unwrap_or(asked)
        };
        let state = Arc::new(Mutex::new(State {
            vt: Vt::new(
                dim("cols", options.cols),
                dim("rows", options.rows),
                options.history as usize,
            ),
            dirty: false,
            exit: None,
            ended: false,
            last_frame: None,
        }));
        let wake = Arc::new(Notify::new());
        let bytes = Arc::new(AtomicU64::new(0));
        let (hold_credit, hold_rx) = watch::channel(false);
        let task = tokio::spawn(pump(Pump {
            client: client.clone(),
            channel,
            frames: client.stream(channel),
            state: state.clone(),
            wake: wake.clone(),
            bytes: bytes.clone(),
            hold: hold_rx,
        }));
        let inner = Inner {
            client,
            channel,
            state,
            wake,
            reading: tokio::sync::Mutex::new(()),
            bytes,
            frames: AtomicU64::new(0),
            hold_credit,
            detached: AtomicBool::new(false),
            observe: options.observe,
        };
        Ok(Self {
            inner: Arc::new(inner),
            task,
        })
    }

    /// Test hook: while held, the view keeps reading but stops returning
    /// CREDIT, as a phone too slow to keep up would; on release it returns
    /// what it owes. herdr-core then repaints instead of sending the backlog.
    #[doc(hidden)]
    pub fn hold_credit(&self, held: bool) {
        let _ = self.inner.hold_credit.send(held);
    }

    #[doc(hidden)]
    pub fn stats(&self) -> ViewStats {
        ViewStats {
            bytes: self.inner.bytes.load(Ordering::Relaxed),
            frames: self.inner.frames.load(Ordering::Relaxed),
        }
    }
}

impl Inner {
    async fn next(&self) -> Result<TerminalUpdate, CoreError> {
        let _one = self.reading.lock().await;
        loop {
            let wait = {
                let mut st = self.state.lock().expect("state lock");
                if st.dirty {
                    match st.last_frame.map(|t| t.elapsed()) {
                        Some(e) if e < FRAME_INTERVAL => Some(FRAME_INTERVAL - e),
                        _ => {
                            st.dirty = false;
                            st.last_frame = Some(Instant::now());
                            let frame = st.vt.take_frame();
                            self.frames.fetch_add(1, Ordering::Relaxed);
                            return Ok(TerminalUpdate::Screen { frame });
                        }
                    }
                } else if let Some(exit) = st.exit.take() {
                    return Ok(TerminalUpdate::Exited { exit });
                } else if st.ended {
                    return Err(CoreError::Closed);
                } else {
                    None
                }
            };
            match wait {
                // Let more output gather into this frame.
                Some(d) => tokio::time::sleep(d).await,
                None => self.wake.notified().await,
            }
        }
    }

    async fn input(&self, bytes: &[u8]) -> Result<(), CoreError> {
        if self.observe {
            return Err(protocol("an observe-mode view cannot type"));
        }
        if bytes.is_empty() {
            return Ok(());
        }
        self.client.send(self.channel, KIND_DATA, bytes).await
    }

    async fn resize(&self, cols: u16, rows: u16) -> Result<(), CoreError> {
        if self.observe {
            return Err(protocol("an observe-mode view cannot resize"));
        }
        let (cols, rows) = (cols.max(1), rows.max(1));
        let mut payload = cols.to_le_bytes().to_vec();
        payload.extend_from_slice(&rows.to_le_bytes());
        self.client
            .send(self.channel, KIND_RESIZE, &payload)
            .await?;
        // herdr-core sends no RESIZE back to the viewer that set the size.
        self.state.lock().expect("state lock").vt.resize(cols, rows);
        self.state.lock().expect("state lock").dirty = true;
        self.wake.notify_one();
        Ok(())
    }

    async fn detach(&self) {
        if self.detached.swap(true, Ordering::SeqCst) {
            return;
        }
        {
            let mut st = self.state.lock().expect("state lock");
            st.ended = true;
            st.dirty = false;
            st.exit = None;
        }
        self.wake.notify_one();
        // Local teardown is immediate; a stalled Host cannot keep detach
        // (including Android's NonCancellable cleanup) waiting indefinitely.
        let _ = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            self.client.send(self.channel, KIND_CLOSE, &[]),
        )
        .await;
    }
}

#[uniffi::export]
impl TerminalView {
    /// The next screen change: right away for the first paint, then each
    /// time the screen changes (at most one frame per 16 ms; changes in
    /// between are merged). After the program ends, one
    /// [`TerminalUpdate::Exited`]; after that, fails with [`CoreError::Closed`]
    /// unless a local search/scroll operation queued another screen frame.
    /// Also fails when the connection or the view ends.
    pub async fn next(&self) -> Result<TerminalUpdate, CoreError> {
        let inner = self.inner.clone();
        crate::on_runtime(async move { inner.next().await }).await
    }

    /// Whether this view watches only (`observe: true` at attach). Typing
    /// needs a new attach with `observe: false`.
    pub fn observing(&self) -> bool {
        self.inner.observe
    }

    /// Types `text` (sent as UTF-8, unchanged: a `\n` is a line feed, not
    /// Enter). Fails on an observing view.
    pub async fn send_text(&self, text: String) -> Result<(), CoreError> {
        let inner = self.inner.clone();
        crate::on_runtime(async move { inner.input(text.as_bytes()).await }).await
    }

    /// Pastes an edited draft in one DATA frame without pressing Enter.
    /// Uses the live VT's DEC 2004 mode: bracket and strip embedded end
    /// markers when enabled, otherwise normalize CRLF/LF to CR.
    /// Fails on an observing view, just like ordinary typing.
    pub async fn paste_text(&self, text: String) -> Result<(), CoreError> {
        let inner = self.inner.clone();
        crate::on_runtime(async move {
            let bytes = inner
                .state
                .lock()
                .expect("state lock")
                .vt
                .paste_bytes(&text);
            inner.input(&bytes).await
        })
        .await
    }

    /// Presses `key` with `mods`, encoded as xterm does and following the
    /// program's cursor-key mode. Ctrl-C (`Char { text: "c" }` + ctrl) is
    /// the interrupt. Fails on an observing view.
    pub async fn send_key(&self, key: TerminalKey, mods: KeyMods) -> Result<(), CoreError> {
        let inner = self.inner.clone();
        crate::on_runtime(async move {
            let app_cursor = inner.state.lock().expect("state lock").vt.app_cursor();
            inner.input(&keys::encode(&key, mods, app_cursor)).await
        })
        .await
    }

    /// Sets the Terminal's grid (input mode: the phone decides the size).
    /// The next frame is a full one at the new size. Fails on an observing view.
    pub async fn resize(&self, cols: u16, rows: u16) -> Result<(), CoreError> {
        let inner = self.inner.clone();
        crate::on_runtime(async move { inner.resize(cols, rows).await }).await
    }

    /// Shows the screen `lines` lines back into the scrollback (0 = the
    /// live screen), stopping at the oldest line; returns where it stopped.
    /// The next frame is the whole new viewport. Only the phone's view
    /// moves: nothing is sent to the Terminal.
    pub fn scroll_to(&self, lines: u32) -> u32 {
        let mut st = self.inner.state.lock().expect("state lock");
        let at = st.vt.scroll_to(lines);
        st.dirty = true;
        drop(st);
        self.inner.wake.notify_one();
        at
    }

    /// Search this view's retained VT, including soft-wrapped lines. An empty
    /// query clears the search. Selects and reveals the oldest match; it never
    /// types into or resizes the remote Terminal. Invalid regex leaves the
    /// previous search intact. Matching uses Alacritty's bounded regex engine.
    pub fn search(
        &self,
        query: String,
        case_sensitive: bool,
        regex: bool,
    ) -> Result<(), CoreError> {
        let mut st = self.inner.state.lock().expect("state lock");
        st.vt.search(&query, case_sensitive, regex)?;
        st.dirty = true;
        drop(st);
        self.inner.wake.notify_one();
        Ok(())
    }

    /// Selects and reveals the next (or previous) match, wrapping at either end.
    /// New output refreshes results on the next frame without moving the view.
    pub fn search_next(&self, backwards: bool) {
        let mut st = self.inner.state.lock().expect("state lock");
        st.vt.search_next(backwards);
        st.dirty = true;
        drop(st);
        self.inner.wake.notify_one();
    }

    /// Detaches from the Terminal (the program keeps running). Later `next`
    /// calls fail with [`CoreError::Closed`].
    pub async fn detach(&self) {
        self.task.abort();
        let inner = self.inner.clone();
        let _ = crate::on_runtime(async move {
            inner.detach().await;
            Ok(())
        })
        .await;
    }
}

impl Drop for TerminalView {
    fn drop(&mut self) {
        self.task.abort();
        let inner = self.inner.clone();
        crate::runtime().spawn(async move { inner.detach().await });
    }
}

struct Pump {
    client: Arc<HcClient>,
    channel: u32,
    frames: mpsc::UnboundedReceiver<Frame>,
    state: Arc<Mutex<State>>,
    wake: Arc<Notify>,
    bytes: Arc<AtomicU64>,
    hold: watch::Receiver<bool>,
}

/// Reads the stream into the VT and credits each DATA once it is parsed.
async fn pump(mut p: Pump) {
    let mut owed: u64 = 0;
    loop {
        let deadline = p
            .state
            .lock()
            .expect("state lock")
            .vt
            .sync_deadline()
            .map(Instant::from_std);
        let frame = tokio::select! {
            f = p.frames.recv() => f,
            _ = sleep_until(deadline) => {
                p.state.lock().expect("state lock").vt.flush_sync();
                mark_dirty(&p);
                continue;
            }
            changed = p.hold.changed() => {
                if changed.is_ok() && !*p.hold.borrow() && owed > 0 {
                    if credit(&p, owed).await.is_err() {
                        break;
                    }
                    owed = 0;
                }
                continue;
            }
        };
        let Some(Frame { kind, payload }) = frame else {
            break;
        };
        match kind {
            KIND_DATA => {
                p.state.lock().expect("state lock").vt.feed(&payload);
                p.bytes.fetch_add(payload.len() as u64, Ordering::Relaxed);
                mark_dirty(&p);
                owed += payload.len() as u64;
                if !*p.hold.borrow() {
                    if credit(&p, owed).await.is_err() {
                        break;
                    }
                    owed = 0;
                }
            }
            KIND_RESIZE if payload.len() >= 4 => {
                let cols = u16::from_le_bytes([payload[0], payload[1]]);
                let rows = u16::from_le_bytes([payload[2], payload[3]]);
                p.state.lock().expect("state lock").vt.resize(cols, rows);
                mark_dirty(&p);
            }
            KIND_CLOSE => {
                let mut st = p.state.lock().expect("state lock");
                st.exit = exit_of(&payload);
                break;
            }
            _ => {}
        }
    }
    p.state.lock().expect("state lock").ended = true;
    p.wake.notify_one();
}

fn mark_dirty(p: &Pump) {
    p.state.lock().expect("state lock").dirty = true;
    p.wake.notify_one();
}

async fn credit(p: &Pump, bytes: u64) -> Result<(), CoreError> {
    // CREDIT is a u32 byte count; a large debt goes in several frames.
    let mut left = bytes;
    while left > 0 {
        let n = left.min(u32::MAX as u64) as u32;
        p.client
            .send(p.channel, KIND_CREDIT, &n.to_le_bytes())
            .await?;
        left -= n as u64;
    }
    Ok(())
}

async fn sleep_until(deadline: Option<Instant>) {
    match deadline {
        Some(d) => tokio::time::sleep_until(d).await,
        None => std::future::pending().await,
    }
}

/// `{"reason":"exited","exit_code":0,"signal":null}`; an empty CLOSE (the
/// answer to our own detach) is no exit.
fn exit_of(payload: &[u8]) -> Option<TerminalExit> {
    let v: Value = serde_json::from_slice(payload).ok()?;
    Some(TerminalExit {
        reason: v["reason"].as_str()?.to_owned(),
        exit_code: v["exit_code"].as_i64().map(|c| c as i32),
        signal: v["signal"].as_str().map(str::to_owned),
    })
}
