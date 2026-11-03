// Aries status for Pi. Installed and kept up to date by the Aries Beacon;
// the Beacon leaves this file alone once you edit it, and removes it when
// the Beacon is removed (unless you edited it).
//
// Inside a herdr-core Terminal it:
// - reports Program Status as OSC 7501 (idle, working, done, error), so
//   Aries shows the Pi Agent's state and notifies on done/error;
// - labels the Terminal with the session file Pi writes
//   (aries.pi.session, aries.pi.file), so Aries can show its Chat.
// Outside herdr-core (no HC_TERMINAL_ID) it does nothing.
import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const ESC = "\x1b";

function osc7501(fields: Record<string, string | undefined>) {
  const body = Object.entries(fields)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${k}=${v}`)
    .join(":");
  process.stdout.write(`${ESC}]7501;${body}${ESC}\\`);
}

const b64 = (s: string) => Buffer.from(s, "utf8").toString("base64");

// `hc` is herdr-core's short name; fall back to the full one.
const HC = ["hc", "herdr-core", join(homedir(), ".local/bin/herdr-core")];

function label(args: string[], i = 0) {
  if (i >= HC.length) return;
  execFile(HC[i], args, (err: any) => {
    if (err && err.code === "ENOENT") label(args, i + 1);
  });
}

function agentDir(): string {
  const d = process.env.PI_CODING_AGENT_DIR;
  if (!d) return join(homedir(), ".pi", "agent");
  return d === "~" ? homedir() : d.startsWith("~/") ? join(homedir(), d.slice(2)) : d;
}

function readJson(path: string): any {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return undefined;
  }
}

// Pi's agent-level retry settings (global, then project): a retried run
// must stay `working` instead of flashing `error` once per attempt.
function retrySettings(cwd: string) {
  const out = { enabled: true, maxRetries: 3, baseDelayMs: 2000 };
  for (const file of [join(agentDir(), "settings.json"), join(cwd, ".pi", "settings.json")]) {
    const r = readJson(file)?.retry;
    if (!r || typeof r !== "object") continue;
    if (typeof r.enabled === "boolean") out.enabled = r.enabled;
    if (typeof r.maxRetries === "number") out.maxRetries = r.maxRetries;
    if (typeof r.baseDelayMs === "number") out.baseDelayMs = r.baseDelayMs;
  }
  return out;
}

// Same errors Pi retries (AgentSession._isRetryableError).
const RETRYABLE =
  /overloaded|provider.?returned.?error|rate.?limit|too many requests|429|500|502|503|504|service.?unavailable|server.?error|internal.?error|network.?error|connection.?error|connection.?refused|connection.?lost|websocket.?closed|websocket.?error|other side closed|fetch failed|upstream.?connect|reset before headers|socket hang up|ended without|http2 request did not get a response|timed? out|timeout|terminated|retry delay/i;

export default function (pi: any) {
  const terminal = process.env.HC_TERMINAL_ID;
  if (!terminal) return;

  const pair = (ctx: any) => {
    const file = ctx?.sessionManager?.getSessionFile?.();
    const id = ctx?.sessionManager?.getSessionId?.();
    if (!file) return;
    const labels = [`aries.pi.file=${file}`];
    if (id) labels.unshift(`aries.pi.session=${id}`);
    label(["label", terminal, ...labels]);
  };

  let lastError = "";
  let attempts = 0;
  let pending: ReturnType<typeof setTimeout> | null = null;
  const clearPending = () => {
    if (pending) clearTimeout(pending);
    pending = null;
  };

  pi.on("session_start", (event: any, ctx: any) => {
    pair(ctx);
    if (event?.reason !== "reload") osc7501({ state: "idle", app: "pi" });
  });

  pi.on("session_shutdown", () => clearPending());

  pi.on("agent_start", () => {
    clearPending();
    lastError = "";
    osc7501({ state: "working", app: "pi" });
  });

  pi.on("message_end", (event: any) => {
    const m = event?.message;
    if (m?.role !== "assistant") return;
    if (m.stopReason === "error") lastError = m.errorMessage || "error";
    else attempts = 0;
  });

  pi.on("agent_end", (_event: any, ctx: any) => {
    pair(ctx);
    if (!lastError) {
      attempts = 0;
      osc7501({ state: "done", app: "pi" });
      return;
    }
    const err = lastError;
    const retry = retrySettings(ctx?.cwd ?? process.cwd());
    if (retry.enabled && RETRYABLE.test(err) && attempts < retry.maxRetries) {
      attempts++;
      osc7501({
        state: "working",
        app: "pi",
        msg: b64(`retrying ${attempts}/${retry.maxRetries}: ${err}`),
      });
      // No retry starts when the user cancels during the backoff: settle
      // on the error after Pi's delay has passed.
      const delay = retry.baseDelayMs * 2 ** (attempts - 1) + 1500;
      pending = setTimeout(() => {
        pending = null;
        attempts = 0;
        osc7501({ state: "error", app: "pi", msg: b64(err) });
      }, delay);
      pending.unref?.();
      return;
    }
    attempts = 0;
    osc7501({ state: "error", app: "pi", msg: b64(err) });
  });
}
