// Aries: tells herdr-core what this OpenCode is doing (OSC 7501 status) and
// which session it shows (Terminal label aries.opencode.session), so the Aries
// app can show the Agent's status and its Chat. Installed and updated by the
// Aries Beacon, removed when the Beacon is removed. Edit it and the Beacon
// leaves your copy alone.
//
// Does nothing outside a herdr-core Terminal (no HC_TERMINAL_ID).
import fs from "node:fs";
import net from "node:net";
import { execFile } from "node:child_process";

const TERMINAL = process.env.HC_TERMINAL_ID;
const SOCKET = process.env.HC_TERMINAL_SOCKET;
const LABEL = "aries.opencode.session";

let tty;
function osc(fields) {
  const body = Object.entries(fields)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${k}=${v}`)
    .join(":");
  try {
    if (tty === undefined) tty = fs.openSync("/dev/tty", "w");
    fs.writeSync(tty, `\x1b]7501;${body}\x1b\\`);
  } catch {
    tty = null;
  }
}

let lastState;
let lastKind;
function report(state, kind, msg) {
  if (state === lastState && kind === lastKind) return;
  lastState = state;
  lastKind = kind;
  osc({
    state,
    kind,
    app: "opencode",
    msg: msg ? Buffer.from(String(msg)).toString("base64") : undefined,
  });
}

// One JSON-RPC call on herdr-core's socket: hello, then the call.
function rpc(method, params) {
  return new Promise((resolve, reject) => {
    if (!SOCKET) return reject(new Error("no HC_TERMINAL_SOCKET"));
    const sock = net.createConnection(SOCKET);
    let buf = "";
    const done = (err, value) => {
      clearTimeout(timer);
      sock.destroy();
      err ? reject(err) : resolve(value);
    };
    const timer = setTimeout(() => done(new Error("timeout")), 3000);
    sock.on("error", (e) => done(e));
    sock.on("connect", () => {
      const hello = { jsonrpc: "2.0", id: 0, method: "hello", params: { generation: 1 } };
      const call = { jsonrpc: "2.0", id: 1, method, params };
      sock.write(JSON.stringify(hello) + "\n" + JSON.stringify(call) + "\n");
    });
    sock.on("data", (chunk) => {
      buf += chunk.toString("utf8");
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl);
        buf = buf.slice(nl + 1);
        let msg;
        try {
          msg = JSON.parse(line);
        } catch {
          continue;
        }
        if (msg.id !== 1) continue;
        return msg.error ? done(new Error(msg.error.message ?? "rpc error")) : done(null, msg.result);
      }
    });
  });
}

let labelled;
function label(sessionID) {
  if (sessionID === labelled) return;
  labelled = sessionID;
  rpc("terminal.label", { terminal: TERMINAL, labels: { [LABEL]: sessionID } }).catch(() => {
    execFile("hc", ["label", TERMINAL, `${LABEL}=${sessionID}`], () => {});
  });
}

const children = new Set(); // sub-agent sessions (parentID set)
const pending = new Map(); // open permission/question request id -> kind

function pair(sessionID) {
  if (sessionID && !children.has(sessionID)) label(sessionID);
}

function settle() {
  if (pending.size === 0) return report("working");
  const kinds = [...pending.values()];
  report("blocked", kinds.includes("permission") ? "permission" : "question");
}

export const AriesAgentState = async () => {
  if (!TERMINAL) return {};
  // A label left by an OpenCode that ran here before is not this session.
  label("");
  return {
    "chat.message": async (input) => {
      pair(input?.sessionID);
      if (pending.size === 0) report("working");
    },
    event: async ({ event }) => {
      const p = event?.properties ?? {};
      const info = p.info;
      if (info?.id && info.parentID) children.add(info.id);
      const sid = p.sessionID ?? info?.id;
      const child = sid !== undefined && children.has(sid);
      switch (event?.type) {
        case "session.created":
        case "session.updated":
          if (!child && sid) pair(sid);
          break;
        case "session.status": {
          if (child) break;
          const st = p.status?.type;
          if (st === "busy" || st === "retry") {
            if (pending.size === 0) report("working");
          } else if (st === "idle" && pending.size === 0 && lastState !== "error") {
            report("idle");
          }
          break;
        }
        case "session.idle":
          // An error stays until the next prompt: OpenCode goes idle right after it.
          if (!child && pending.size === 0 && lastState !== "error") report("idle");
          break;
        case "session.error":
          if (child || p.error?.name === "MessageAbortedError") break;
          report("error", undefined, p.error?.data?.message ?? p.error?.name ?? "error");
          break;
        case "permission.asked":
          pending.set(p.id, "permission");
          settle();
          break;
        case "question.asked":
          pending.set(p.id, "question");
          settle();
          break;
        case "permission.replied":
        case "question.replied":
        case "question.rejected":
          pending.delete(p.requestID ?? p.id);
          settle();
          break;
      }
    },
  };
};
