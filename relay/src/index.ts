/**
 * Aries Relay: one-way push delivery (Host -> phone).
 *
 * Stores only pairing/challenge hashes, verification IDs, device push tokens and
 * rate limit counters (D1). Envelopes are forwarded to FCM and never
 * stored, queued or logged: nothing here may log a request body.
 *
 * API:
 * - `POST /v1/pairings` `{platform: "fcm", device_token, verification_id}`
 *   -> 202 `{token, status: "pending", expires_in: 120}`. The phone first
 *   persists its random verification ID and Relay URL, then requests this.
 * - FCM data `{v: "1", kind: "pairing_challenge", verification_id, challenge}`
 *   carries a fresh proof ONLY to the proposed device. No callback URL.
 * - `POST /v1/pairing/confirm` `{verification_id, challenge}` -> 204, once.
 * - `GET /v1/pairing` (Bearer token) -> `{status, verification_id}`; the ID
 *   is the last completed proof, so an expired rotation cannot look verified.
 * - `PUT /v1/pairing` (Bearer token) `{device_token, verification_id}` -> 202;
 *   delivery stays on the old device until the new challenge is confirmed.
 * - `DELETE /v1/pairing` (Bearer token) -> 204, also for unknown tokens.
 *   Deletes the pairing, pending challenge and its counters at once.
 * - `POST /v1/push` (Bearer token), envelope bytes -> 202 accepted by FCM;
 *   401 unknown token, 403 verification pending, 413 too large, 429 rate
 *   limited (`Retry-After`), 410 device gone, 502 FCM failed.
 */

import { type FcmConfig, FcmError, sendFcm } from "./fcm";

export interface Env {
  DB: D1Database;
  /** Service account key JSON for FCM (`wrangler secret put FCM_SERVICE_ACCOUNT_JSON`). */
  FCM_SERVICE_ACCOUNT_JSON?: string;
  /** If set, must equal the key's `client_email` (guards a wrong key). */
  FCM_SERVICE_ACCOUNT_EMAIL?: string;
  /** Pushes per pairing token per minute (default 30). */
  PUSH_LIMIT_PER_MINUTE?: string;
  /** New pairings per client IP per hour (default 20). */
  PAIR_LIMIT_PER_HOUR?: string;
  /** Optional: how FCM and Google OAuth are reached (a service binding, or a fake in tests). */
  FCM?: { fetch: typeof fetch };
}

/** Largest envelope accepted: base64 of it must fit FCM's 4 KiB data limit. */
export const MAX_ENVELOPE_BYTES = 2900;

const CHALLENGE_SECONDS = 120;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      // The message only: never the request.
      console.error("relay error", e instanceof Error ? e.message : "unknown");
      return json(500, { error: "internal" });
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const { pathname } = new URL(request.url);
  const m = request.method;
  if (pathname === "/v1/pairings" && m === "POST") return createPairing(request, env);
  if (pathname === "/v1/pairing/confirm" && m === "POST") return confirmPairing(request, env);
  if (pathname === "/v1/pairing" && m === "GET") return pairingStatus(request, env);
  if (pathname === "/v1/pairing" && m === "PUT") return updatePairing(request, env);
  if (pathname === "/v1/pairing" && m === "DELETE") return deletePairing(request, env);
  if (pathname === "/v1/push" && m === "POST") return push(request, env);
  return json(404, { error: "not_found" });
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function base64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function bearer(request: Request): string | null {
  const h = request.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(\S+)$/i.exec(h);
  return m ? m[1] : null;
}

function limit(value: string | undefined, fallback: number): number {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

/**
 * Counts one event for `key` in the current window of `windowSeconds`;
 * returns seconds to wait when over `max`, else 0. Older windows of the
 * key are dropped.
 */
async function rateLimit(env: Env, key: string, windowSeconds: number, max: number): Promise<number> {
  const now = Math.floor(Date.now() / 1000);
  const window = now - (now % windowSeconds);
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key, window_start, count) VALUES (?1, ?2, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET count = count + 1
     RETURNING count`,
  )
    .bind(key, window)
    .first<{ count: number }>();
  await env.DB.prepare("DELETE FROM rate_limits WHERE key = ?1 AND window_start < ?2").bind(key, window).run();
  const count = row?.count ?? 1;
  return count > max ? window + windowSeconds - now : 0;
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const v = await request.json();
    return v && typeof v === "object" ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function deviceToken(body: Record<string, unknown> | null): string | null {
  const t = body?.device_token;
  return typeof t === "string" && t.length > 0 && t.length <= 4096 ? t : null;
}

function verificationId(body: Record<string, unknown> | null): string | null {
  const id = body?.verification_id;
  return typeof id === "string" && /^[A-Za-z0-9_-]{43}$/.test(id) ? id : null;
}

async function expireChallenges(env: Env): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM pairings WHERE verified = 0 AND created_at <= ?1").bind(now - CHALLENGE_SECONDS),
    env.DB.prepare("DELETE FROM pairing_challenges WHERE expires_at <= ?1").bind(now),
  ]);
}

async function createPairing(request: Request, env: Env): Promise<Response> {
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
  const wait = await rateLimit(env, `ip:${await sha256Hex(ip)}`, 3600, limit(env.PAIR_LIMIT_PER_HOUR, 20));
  if (wait > 0) return json(429, { error: "rate_limited" }, { "retry-after": String(wait) });

  const body = await readJson(request);
  const device = deviceToken(body);
  const id = verificationId(body);
  if (body?.platform !== "fcm" || !device || !id) return json(400, { error: "bad_request" });
  await expireChallenges(env);
  const token = base64url(crypto.getRandomValues(new Uint8Array(32)));
  return challengeDevice(env, await sha256Hex(token), device, id, token);
}

async function updatePairing(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return json(401, { error: "unknown_pairing" });
  const hash = await sha256Hex(token);
  await expireChallenges(env);
  const pairing = await env.DB.prepare("SELECT verified FROM pairings WHERE token_hash = ?1").bind(hash).first<{ verified: number }>();
  if (!pairing) return json(401, { error: "unknown_pairing" });
  if (!pairing.verified) return json(403, { error: "verification_pending" });
  const body = await readJson(request);
  const device = deviceToken(body);
  const id = verificationId(body);
  if (!device || !id) return json(400, { error: "bad_request" });
  const wait = await rateLimit(env, `verify:${hash}`, 60, 5);
  if (wait > 0) return json(429, { error: "rate_limited" }, { "retry-after": String(wait) });
  return challengeDevice(env, hash, device, id);
}

async function challengeDevice(env: Env, hash: string, device: string, id: string, token?: string): Promise<Response> {
  let config: FcmConfig;
  try { config = fcmConfig(env); } catch { return json(503, { error: "fcm_not_configured" }); }
  if (await env.DB.prepare("SELECT 1 FROM pairing_challenges WHERE verification_id = ?1").bind(id).first()) {
    return json(409, { error: "verification_conflict" });
  }
  const challenge = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const now = Math.floor(Date.now() / 1000);
  const statements: D1PreparedStatement[] = [];
  if (token) statements.push(env.DB.prepare(
    "INSERT INTO pairings (token_hash, platform, device_token, created_at, verified) VALUES (?1, 'fcm', ?2, ?3, 0)",
  ).bind(hash, device, now));
  statements.push(env.DB.prepare("DELETE FROM pairing_challenges WHERE token_hash = ?1").bind(hash));
  statements.push(env.DB.prepare(
    "INSERT INTO pairing_challenges (verification_id, token_hash, device_token, challenge_hash, expires_at) VALUES (?1, ?2, ?3, ?4, ?5)",
  ).bind(id, hash, device, await sha256Hex(challenge), now + CHALLENGE_SECONDS));
  await env.DB.batch(statements);
  try {
    // No callback URL or pairing bearer is exposed to the recipient. The app
    // resolves this ID against its own locally initiated registration only.
    await sendFcm(config, device, { v: "1", kind: "pairing_challenge", verification_id: id, challenge }, "HIGH", `${CHALLENGE_SECONDS}s`);
  } catch (e) {
    await env.DB.prepare("DELETE FROM pairing_challenges WHERE verification_id = ?1").bind(id).run();
    if (token) await forget(env, hash);
    if (e instanceof FcmError && e.unregistered) return json(410, { error: "device_gone" });
    console.error("fcm challenge failed", e instanceof FcmError ? e.status : "network");
    return json(502, { error: "fcm_failed" });
  }
  return json(202, { ...(token ? { token } : {}), status: "pending", expires_in: CHALLENGE_SECONDS });
}

async function confirmPairing(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const id = verificationId(body);
  const proof = body?.challenge;
  if (!id || typeof proof !== "string" || proof.length > 128) return json(400, { error: "bad_request" });
  await expireChallenges(env);
  const proofHash = await sha256Hex(proof);
  const now = Math.floor(Date.now() / 1000);
  // One atomic transaction: a proof is bound to exactly one pairing and the
  // proposed device token, expires in two minutes, and can be consumed once.
  const results = await env.DB.batch([
    env.DB.prepare(`UPDATE pairings SET verified = 1, verification_id = ?1,
      device_token = (SELECT device_token FROM pairing_challenges WHERE verification_id = ?1)
      WHERE token_hash = (SELECT token_hash FROM pairing_challenges
        WHERE verification_id = ?1 AND challenge_hash = ?2 AND expires_at > ?3)`)
      .bind(id, proofHash, now),
    env.DB.prepare("DELETE FROM pairing_challenges WHERE verification_id = ?1 AND challenge_hash = ?2 AND expires_at > ?3")
      .bind(id, proofHash, now),
  ]);
  return results[0].meta.changes > 0 ? new Response(null, { status: 204 }) : json(401, { error: "invalid_challenge" });
}

async function pairingStatus(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return json(401, { error: "unknown_pairing" });
  await expireChallenges(env);
  const row = await env.DB.prepare(`SELECT p.verified, p.verification_id,
    c.verification_id AS pending_id FROM pairings p LEFT JOIN pairing_challenges c USING (token_hash)
    WHERE p.token_hash = ?1`).bind(await sha256Hex(token))
    .first<{ verified: number; verification_id: string | null; pending_id: string | null }>();
  if (!row) return json(401, { error: "unknown_pairing" });
  return json(200, { status: row.pending_id || !row.verified ? "pending" : "active", verification_id: row.verification_id });
}

async function forget(env: Env, hash: string): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM pairing_challenges WHERE token_hash = ?1").bind(hash),
    env.DB.prepare("DELETE FROM pairings WHERE token_hash = ?1").bind(hash),
    env.DB.prepare("DELETE FROM rate_limits WHERE key IN (?1, ?2)").bind(`push:${hash}`, `verify:${hash}`),
  ]);
}

async function deletePairing(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (token) await forget(env, await sha256Hex(token));
  return new Response(null, { status: 204 });
}

async function push(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return json(401, { error: "unknown_pairing" });
  const hash = await sha256Hex(token);
  await expireChallenges(env);
  const pairing = await env.DB.prepare("SELECT device_token, verified FROM pairings WHERE token_hash = ?1")
    .bind(hash)
    .first<{ device_token: string; verified: number }>();
  if (!pairing) return json(401, { error: "unknown_pairing" });
  if (!pairing.verified) return json(403, { error: "verification_pending" });

  const wait = await rateLimit(env, `push:${hash}`, 60, limit(env.PUSH_LIMIT_PER_MINUTE, 30));
  if (wait > 0) return json(429, { error: "rate_limited" }, { "retry-after": String(wait) });

  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_ENVELOPE_BYTES) return json(413, { error: "too_large" });
  const envelope = new Uint8Array(await request.arrayBuffer());
  if (envelope.length === 0) return json(400, { error: "bad_request" });
  if (envelope.length > MAX_ENVELOPE_BYTES) return json(413, { error: "too_large" });

  let config: FcmConfig;
  try {
    config = fcmConfig(env);
  } catch {
    return json(503, { error: "fcm_not_configured" });
  }
  try {
    // Delivery priority is transport metadata; the Relay still cannot read the envelope.
    const priority = request.headers.get("x-aries-priority") === "normal" ? "NORMAL" : "HIGH";
    await sendFcm(config, pairing.device_token, { v: "1", e: base64(envelope) }, priority);
  } catch (e) {
    if (e instanceof FcmError && e.unregistered) {
      await forget(env, hash);
      return json(410, { error: "device_gone" });
    }
    console.error("fcm send failed", e instanceof FcmError ? e.status : "network");
    return json(502, { error: "fcm_failed" });
  }
  return new Response(null, { status: 202 });
}

function fcmConfig(env: Env): FcmConfig {
  const key = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON ?? "");
  if (typeof key.client_email !== "string" || typeof key.private_key !== "string" || typeof key.project_id !== "string") {
    throw new Error("incomplete service account key");
  }
  if (env.FCM_SERVICE_ACCOUNT_EMAIL && env.FCM_SERVICE_ACCOUNT_EMAIL !== key.client_email) {
    throw new Error("service account key is for another account");
  }
  return {
    clientEmail: key.client_email,
    privateKey: key.private_key,
    projectId: key.project_id,
    tokenUri: typeof key.token_uri === "string" ? key.token_uri : "https://oauth2.googleapis.com/token",
    // Workers throw "Illegal invocation" when the global fetch is called as a method.
    fetch: env.FCM ? env.FCM.fetch.bind(env.FCM) : (input, init) => fetch(input, init),
  };
}
