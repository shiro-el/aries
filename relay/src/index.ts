/**
 * Aries Relay: one-way push delivery (Host -> phone).
 *
 * Stores only pairing tokens (as SHA-256 hashes), device push tokens and
 * rate limit counters (D1). Envelopes are forwarded to FCM and never
 * stored, queued or logged: nothing here may log a request body.
 *
 * API:
 * - `POST /v1/pairings` `{platform: "fcm", device_token}` -> 201 `{token}`.
 *   Rate limited per client IP (hashed).
 * - `PUT /v1/pairing` (Bearer token) `{device_token}` -> 204 (FCM rotated it).
 * - `DELETE /v1/pairing` (Bearer token) -> 204, also for unknown tokens.
 *   Deletes the pairing and its counters at once.
 * - `POST /v1/push` (Bearer token), body = the envelope bytes -> 202 once
 *   FCM accepted it. 401 unknown token, 413 too large, 429 rate limited
 *   (`Retry-After`), 410 the phone is gone (the pairing is deleted),
 *   502 FCM failed.
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

async function createPairing(request: Request, env: Env): Promise<Response> {
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
  const wait = await rateLimit(env, `ip:${await sha256Hex(ip)}`, 3600, limit(env.PAIR_LIMIT_PER_HOUR, 20));
  if (wait > 0) return json(429, { error: "rate_limited" }, { "retry-after": String(wait) });

  const body = await readJson(request);
  const device = deviceToken(body);
  if (body?.platform !== "fcm" || !device) return json(400, { error: "bad_request" });

  const token = base64url(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.prepare(
    "INSERT INTO pairings (token_hash, platform, device_token, created_at) VALUES (?1, ?2, ?3, ?4)",
  )
    .bind(await sha256Hex(token), "fcm", device, Math.floor(Date.now() / 1000))
    .run();
  return json(201, { token });
}

async function updatePairing(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  const device = deviceToken(await readJson(request));
  if (!token) return json(401, { error: "unknown_pairing" });
  if (!device) return json(400, { error: "bad_request" });
  const r = await env.DB.prepare("UPDATE pairings SET device_token = ?1 WHERE token_hash = ?2")
    .bind(device, await sha256Hex(token))
    .run();
  return r.meta.changes > 0 ? new Response(null, { status: 204 }) : json(401, { error: "unknown_pairing" });
}

async function forget(env: Env, hash: string): Promise<void> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM pairings WHERE token_hash = ?1").bind(hash),
    env.DB.prepare("DELETE FROM rate_limits WHERE key = ?1").bind(`push:${hash}`),
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
  const pairing = await env.DB.prepare("SELECT device_token FROM pairings WHERE token_hash = ?1")
    .bind(hash)
    .first<{ device_token: string }>();
  if (!pairing) return json(401, { error: "unknown_pairing" });

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
    await sendFcm(config, pairing.device_token, { v: "1", e: base64(envelope) });
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
    fetch: env.FCM ? env.FCM.fetch.bind(env.FCM) : fetch,
  };
}
