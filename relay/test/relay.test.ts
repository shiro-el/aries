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
  await env.DB.batch([env.DB.prepare("DELETE FROM pairing_challenges"), env.DB.prepare("DELETE FROM pairings"), env.DB.prepare("DELETE FROM rate_limits")]);
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

function call(method: string, path: string, opts: { token?: string; json?: unknown; body?: Uint8Array; ip?: string; priority?: string } = {}) {
  const headers: Record<string, string> = { "cf-connecting-ip": opts.ip ?? "203.0.113.7" };
  if (opts.priority) headers["x-aries-priority"] = opts.priority;
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

function freshId(): string {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function pairPhone(device = "fcm-device-1"): Promise<string> {
  const id = freshId();
  const r = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: device, verification_id: id } });
  expect(r.status).toBe(202);
  const { token } = (await r.json()) as { token: string };
  expect((await call("POST", "/v1/pairing/confirm", { json: fcm.sent.at(-1)!.message.data })).status).toBe(204);
  fcm.sent.length = 0;
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

describe("token ownership", () => {
  it("requires the proof delivered to the device before accepting envelopes", async () => {
    const verificationId = "a".repeat(43);
    const r = await call("POST", "/v1/pairings", {
      json: { platform: "fcm", device_token: "victim-phone", verification_id: verificationId },
    });
    expect(r.status).toBe(202);
    const body = await r.json() as { token: string; status: string };
    expect(body.status).toBe("pending");
    expect((await call("POST", "/v1/push", { token: body.token, body: envelope() })).status).toBe(403);
    const data = fcm.sent[0].message.data;
    expect(data).toMatchObject({ v: "1", kind: "pairing_challenge", verification_id: verificationId });
    expect(data.challenge).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(JSON.stringify(body)).not.toContain(data.challenge);
    expect(await everythingStored()).not.toContain(data.challenge);
    expect((await call("POST", "/v1/pairing/confirm", { json: { verification_id: verificationId, challenge: "wrong" } })).status).toBe(401);
    expect((await call("POST", "/v1/pairing/confirm", { json: data })).status).toBe(204);
    expect((await call("GET", "/v1/pairing", { token: body.token })).status).toBe(200);
    expect((await call("POST", "/v1/push", { token: body.token, body: envelope() })).status).toBe(202);
    expect((await call("POST", "/v1/pairing/confirm", { json: data })).status).toBe(401);
  });
});

describe("ownership lifecycle", () => {
  it("keeps legacy registrations usable and verifies a replacement before switching delivery", async () => {
    // Seed an actual pre-ownership database, then apply the real upgrade.
    await env.DB.batch([
      env.DB.prepare("DROP TABLE pairing_challenges"),
      env.DB.prepare("DROP TABLE pairings"),
      env.DB.prepare("DROP TABLE rate_limits"),
    ]);
    await env.DB.batch(env.TEST_MIGRATIONS[0].queries.map(q => env.DB.prepare(q)));
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode("legacy-token")))].map(b => b.toString(16).padStart(2, "0")).join("");
    await env.DB.prepare("INSERT INTO pairings (token_hash, platform, device_token, created_at) VALUES (?1, 'fcm', 'old-phone', 1)").bind(hash).run();
    for (const migration of env.TEST_MIGRATIONS.slice(1)) {
      await env.DB.batch(migration.queries.map(q => env.DB.prepare(q)));
    }
    const token = "legacy-token";
    expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(202);
    const id = freshId();
    expect((await call("PUT", "/v1/pairing", { token, json: { device_token: "new-phone", verification_id: id } })).status).toBe(202);
    const challenge = fcm.sent.at(-1)!.message.data;
    expect(fcm.sent.at(-1)!.message.token).toBe("new-phone");
    expect((await (await call("GET", "/v1/pairing", { token })).json() as {status: string}).status).toBe("pending");
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent.at(-1)!.message.token).toBe("old-phone");
    expect((await call("POST", "/v1/pairing/confirm", { json: challenge })).status).toBe(204);
    const status = await (await call("GET", "/v1/pairing", { token })).json();
    expect(status).toEqual({ status: "active", verification_id: id });
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent.at(-1)!.message.token).toBe("new-phone");
  });

  it("expires unanswered registrations and rotations without disabling the verified device", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    try {
      vi.setSystemTime(new Date("2026-11-03T10:00:00Z"));
      const token = await pairPhone("old-phone");
      await call("PUT", "/v1/pairing", { token, json: { device_token: "new-phone", verification_id: freshId() } });
      const rotation = fcm.sent.at(-1)!.message.data;
      const pending = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: "unreachable", verification_id: freshId() } });
      const pendingToken = (await pending.json() as {token: string}).token;
      const registration = fcm.sent.at(-1)!.message.data;
      expect(fcm.sent.at(-1)!.message.android?.ttl).toBe("120s");
      vi.setSystemTime(new Date("2026-11-03T10:02:00Z"));
      for (const data of [rotation, registration]) {
        expect((await call("POST", "/v1/pairing/confirm", { json: data })).status).toBe(401);
      }
      expect((await call("POST", "/v1/push", { token: pendingToken, body: envelope() })).status).toBe(401);
      expect((await call("POST", "/v1/push", { token, body: envelope() })).status).toBe(202);
      expect(fcm.sent.at(-1)!.message.token).toBe("old-phone");
    } finally { vi.useRealTimers(); }
  });

  it("binds a proof to its registration and invalidates superseded rotations and deleted pairings", async () => {
    const a = await pairPhone("a");
    const b = await pairPhone("b");
    const first = freshId(), second = freshId();
    await call("PUT", "/v1/pairing", { token: a, json: { device_token: "a2", verification_id: first } });
    const oldProof = fcm.sent.at(-1)!.message.data;
    await call("PUT", "/v1/pairing", { token: b, json: { device_token: "b2", verification_id: second } });
    const otherProof = fcm.sent.at(-1)!.message.data;
    expect((await call("POST", "/v1/pairing/confirm", { json: { ...otherProof, challenge: oldProof.challenge } })).status).toBe(401);
    await call("PUT", "/v1/pairing", { token: a, json: { device_token: "a3", verification_id: freshId() } });
    expect((await call("POST", "/v1/pairing/confirm", { json: oldProof })).status).toBe(401);
    await call("DELETE", "/v1/pairing", { token: b });
    expect((await call("POST", "/v1/pairing/confirm", { json: otherProof })).status).toBe(401);
    expect((await call("GET", "/v1/pairing", { token: "unknown" })).status).toBe(401);
  });

  it("fails registration cleanly when FCM is unavailable and preserves a failed rotation", async () => {
    const token = await pairPhone("old-phone");
    for (const method of ["POST", "PUT"]) {
      fcm.failNext = { status: 503, body: {} };
      const r = await call(method, method === "POST" ? "/v1/pairings" : "/v1/pairing", {
        token, json: { platform: "fcm", device_token: "new-phone", verification_id: freshId() },
      });
      expect(r.status).toBe(502);
    }
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent.at(-1)!.message.token).toBe("old-phone");
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM pairings").first<{n: number}>())?.n).toBe(1);
  });
});

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
    const r = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: "d", verification_id: freshId() } });
    expect(r.status).toBe(429);
    expect(Number(r.headers.get("retry-after"))).toBeGreaterThan(0);
    // Another IP is not affected.
    const other = await call("POST", "/v1/pairings", { json: { platform: "fcm", device_token: "d", verification_id: freshId() }, ip: "198.51.100.1" });
    expect(other.status).toBe(202);
    // The IP itself is not stored, only a hash of it.
    expect(await everythingStored()).not.toContain("203.0.113.7");
  });

  it("updates the device token when FCM rotates it", async () => {
    const token = await pairPhone("old-device");
    expect((await call("PUT", "/v1/pairing", { token, json: { device_token: "new-device", verification_id: freshId() } })).status).toBe(202);
    expect((await call("POST", "/v1/pairing/confirm", { json: fcm.sent.at(-1)!.message.data })).status).toBe(204);
    fcm.sent.length = 0;
    await call("POST", "/v1/push", { token, body: envelope() });
    expect(fcm.sent[0].message.token).toBe("new-device");
    expect((await call("PUT", "/v1/pairing", { token: "unknown", json: { device_token: "x" } })).status).toBe(401);
  });
});

describe("delivery", () => {
  it("allows normal priority for encrypted progress without revealing its content", async () => {
    const token = await pairPhone();
    expect((await call("POST", "/v1/push", { token, body: envelope(), priority: "normal" })).status).toBe(202);
    expect(fcm.sent[0].message.android?.priority).toBe("NORMAL");
    expect((await call("POST", "/v1/push", { token, body: envelope(), priority: "unknown" })).status).toBe(202);
    expect(fcm.sent[1].message.android?.priority).toBe("HIGH");
  });

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
