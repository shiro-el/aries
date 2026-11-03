// Seam 2: the Relay's HTTP API in the Workers runtime with a real (local)
// D1; FCM is the only fake.
import { env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import worker, { type Env } from "../src/index";
import { resetFcmToken } from "../src/fcm";
import { FakeFcm } from "./fake-fcm";

const BASE = "https://relay.test";

let fcm: FakeFcm;
let relayEnv: Env;

beforeEach(async () => {
  await env.DB.batch([env.DB.prepare("DELETE FROM pairings"), env.DB.prepare("DELETE FROM rate_limits")]);
  resetFcmToken();
  fcm = await FakeFcm.create();
  relayEnv = {
    ...env,
    PUSH_LIMIT_PER_MINUTE: "5",
    PAIR_LIMIT_PER_HOUR: "50",
    FCM_SERVICE_ACCOUNT_JSON: fcm.serviceAccountJson,
    FCM_SERVICE_ACCOUNT_EMAIL: fcm.email,
    FCM: { fetch: fcm.fetch },
  };
});

afterEach(() => {
  vi.restoreAllMocks();
});

function call(method: string, path: string, opts: { token?: string; json?: unknown; body?: Uint8Array; ip?: string } = {}) {
  const headers: Record<string, string> = { "cf-connecting-ip": opts.ip ?? "203.0.113.7" };
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  let body: BodyInit | undefined;
  if (opts.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.body) {
    headers["content-type"] = "application/octet-stream";
    body = opts.body;
  }
  return worker.fetch(new Request(`${BASE}${path}`, { method, headers, body }), relayEnv);
}

async function pairPhone(device = "fcm-device-1"): Promise<string> {
  const r = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: device } });
  expect(r.status).toBe(201);
  const { token } = (await r.json()) as { token: string };
  return token;
}

/** A stand-in envelope: 2048 recognisable bytes. */
function envelope(fill = 0xa5): Uint8Array {
  const e = new Uint8Array(2048).fill(fill);
  e[0] = 1;
  return e;
}

function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

/** Every value in every Relay table, as one string. */
async function everythingStored(): Promise<string> {
  const tables = await env.DB.prepare(
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%'",
  ).all<{ name: string }>();
  let dump = "";
  for (const { name } of tables.results) {
    const rows = await env.DB.prepare(`SELECT * FROM "${name}"`).all();
    dump += JSON.stringify(rows.results);
  }
  return dump;
}

describe("pairing", () => {
  it("issues a token and stores only its hash with the device token", async () => {
    const token = await pairPhone("fcm-device-1");
    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const rows = await env.DB.prepare("SELECT * FROM pairings").all<Record<string, unknown>>();
    expect(rows.results).toHaveLength(1);
    expect(rows.results[0].device_token).toBe("fcm-device-1");
    expect(await everythingStored()).not.toContain(token);
  });

  it("rejects a malformed registration", async () => {
    expect((await call("POST", "/v1/pairings", { json: { platform: "apns", device_token: "x" } })).status).toBe(400);
    expect((await call("POST", "/v1/pairings", { json: { platform: "fcm" } })).status).toBe(400);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM pairings").first<{ n: number }>())?.n).toBe(0);
  });

  it("limits new pairings per client IP", async () => {
    relayEnv.PAIR_LIMIT_PER_HOUR = "2";
    await pairPhone();
    await pairPhone();
    const r = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: "d" } });
    expect(r.status).toBe(429);
    expect(Number(r.headers.get("retry-after"))).toBeGreaterThan(0);
    // Another IP is not affected.
    const other = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: "d" }, ip: "198.51.100.1" });
    expect(other.status).toBe(201);
    // The IP itself is not stored, only a hash of it.
    expect(await everythingStored()).not.toContain("203.0.113.7");
  });

  it("updates the device token when FCM rotates it", async () => {
    const token = await pairPhone("old-device");
    expect((await call("PUT", "/v1/pairing", { token, json: { device_token: "new-device" } })).status).toBe(204);
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent[0].message.token).toBe("new-device");
    expect((await call("PUT", "/v1/pairing", { token: "unknown", json: { device_token: "x" } })).status).toBe(401);
  });
});

describe("delivery", () => {
  it("forwards the envelope to the paired device as a high-priority FCM data message", async () => {
    const token = await pairPhone("fcm-device-1");
    const e = envelope();

    const r = await call("POST", "/v1/push", { token, body: e });

    expect(r.status).toBe(202);
    expect(fcm.sent).toHaveLength(1);
    const m = fcm.sent[0];
    expect(m.projectId).toBe("aries-test");
    expect(m.message.token).toBe("fcm-device-1");
    expect(m.message.data).toEqual({ v: "1", e: b64(e) });
    expect(m.message.android?.priority).toBe("HIGH");
  });

  it("reaches FCM through the global fetch when no FCM binding is set (as deployed)", async () => {
    const token = await pairPhone("fcm-device-1");
    // Like the Workers runtime: the global fetch called as a method throws.
    vi.spyOn(globalThis, "fetch").mockImplementation(function (this: unknown, input, init) {
      if (this !== undefined && this !== globalThis) throw new TypeError("Illegal invocation");
      return fcm.fetch(input, init);
    });
    const { FCM: _, ...deployed } = relayEnv;
    relayEnv = deployed;

    const r = await call("POST", "/v1/push", { token, body: envelope() });

    expect(r.status).toBe(202);
    expect(fcm.sent).toHaveLength(1);
  });

  it("never stores or logs the envelope", async () => {
    const token = await pairPhone();
    const e = envelope(0x3c);
    const logged: string[] = [];
    for (const level of ["log", "info", "warn", "error", "debug"] as const) {
      vi.spyOn(console, level).mockImplementation((...args: unknown[]) => {
        logged.push(args.map(String).join(" "));
      });
    }
    fcm.failNext = { status: 500, body: { error: { status: "INTERNAL" } } };
    await call("POST", "/v1/push", { token, body: e });
    await call("POST", "/v1/push", { token, body: e });

    const stored = await everythingStored();
    const text = b64(e);
    expect(stored).not.toContain(text);
    expect(stored).not.toContain(text.slice(0, 40));
    expect(logged.join("\n")).not.toContain(text.slice(0, 40));
  });

  it("reuses the OAuth access token", async () => {
    const token = await pairPhone();
    await call("POST", "/v1/push", { token, body: envelope() });
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent).toHaveLength(2);
    expect(fcm.tokensIssued).toBe(1);
  });

  it("refuses an unknown or missing pairing token", async () => {
    expect((await call("POST", "/v1/push", { token: "nope", body: envelope() })).status).toBe(401);
    expect((await call("POST", "/v1/push", { body: envelope() })).status).toBe(401);
    expect(fcm.sent).toHaveLength(0);
  });

  it("refuses an envelope too large for FCM, and an empty one", async () => {
    const token = await pairPhone();
    expect((await call("POST", "/v1/push", { token, body: new Uint8Array(3000) })).status).toBe(413);
    expect((await call("POST", "/v1/push", { token, body: new Uint8Array(0) })).status).toBe(400);
    expect(fcm.sent).toHaveLength(0);
  });

  it("deletes the pairing when FCM says the device is gone", async () => {
    const token = await pairPhone();
    fcm.failNext = {
      status: 404,
      body: { error: { status: "NOT_FOUND", details: [{ errorCode: "UNREGISTERED" }] } },
    };
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(410);
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(401);
    const stored = await everythingStored();
    expect(stored).not.toContain("token_hash");
    expect(stored).not.toContain("push:");
  });

  it("reports an FCM failure without dropping the pairing", async () => {
    const token = await pairPhone();
    fcm.failNext = { status: 503, body: { error: { status: "UNAVAILABLE" } } };
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(502);
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(202);
  });

  it("is unavailable without an FCM key, or with a key for another account", async () => {
    const token = await pairPhone();
    relayEnv.FCM_SERVICE_ACCOUNT_EMAIL = "someone-else@example.iam.gserviceaccount.com";
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(503);
    relayEnv.FCM_SERVICE_ACCOUNT_JSON = undefined;
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(503);
    expect(fcm.sent).toHaveLength(0);
  });
});

describe("rate limit", () => {
  it("limits pushes per pairing token", async () => {
    const a = await pairPhone("device-a");
    const b = await pairPhone("device-b");
    for (let i = 0; i < 5; i++) {
      expect((await call("POST", "/v1/push", { token: a, body: envelope() })).status).toBe(202);
    }

    const over = await call("POST", "/v1/push", { token: a, body: envelope() });
    expect(over.status).toBe(429);
    const wait = Number(over.headers.get("retry-after"));
    expect(wait).toBeGreaterThan(0);
    expect(wait).toBeLessThanOrEqual(60);
    expect(fcm.sent).toHaveLength(5);
    // Another pairing has its own budget.
    expect((await call("POST", "/v1/push", { token: b, body: envelope() })).status).toBe(202);
  });

  it("starts a new budget in the next window", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    try {
      vi.setSystemTime(new Date("2026-11-03T10:00:05Z"));
      const token = await pairPhone();
      for (let i = 0; i < 5; i++) await call("POST", "/v1/push", { token, body: envelope() });
      expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(429);

      vi.setSystemTime(new Date("2026-11-03T10:01:01Z"));
      expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(202);
      // Old windows are not kept.
      const windows = await env.DB.prepare("SELECT COUNT(*) AS n FROM rate_limits WHERE key LIKE 'push:%'").first<{
        n: number;
      }>();
      expect(windows?.n).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("unpairing", () => {
  it("deletes the pairing and its counters at once", async () => {
    const token = await pairPhone("fcm-device-1");
    await call("POST", "/v1/push", { token, body: envelope() });

    expect((await call("DELETE", "/v1/pairing", { token })).status).toBe(204);

    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(401);
    const stored = await everythingStored();
    expect(stored).not.toContain("fcm-device-1");
    expect(stored).not.toContain("push:");
  });

  it("answers the same for an unknown token (from either side, any number of times)", async () => {
    const token = await pairPhone();
    expect((await call("DELETE", "/v1/pairing", { token })).status).toBe(204);
    expect((await call("DELETE", "/v1/pairing", { token })).status).toBe(204);
    expect((await call("DELETE", "/v1/pairing", { token: "never-issued" })).status).toBe(204);
  });

  it("leaves other pairings alone", async () => {
    const a = await pairPhone("device-a");
    const b = await pairPhone("device-b");
    await call("DELETE", "/v1/pairing", { token: a });
    expect((await call("POST", "/v1/push", { token: b, body: envelope() })).status).toBe(202);
  });
});

it("answers 404 elsewhere", async () => {
  expect((await call("GET", "/")).status).toBe(404);
  expect((await call("GET", "/v1/push")).status).toBe(404);
});
