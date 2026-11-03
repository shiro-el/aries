/**
 * FCM HTTP v1: a service account JWT (RS256) is exchanged for an OAuth
 * access token, cached in memory until shortly before it expires, and used
 * to send one data message per push. Attention uses HIGH (see
 * docs/research/background-choice-reply.md); watched progress uses NORMAL.
 */

export interface FcmConfig {
  clientEmail: string;
  privateKey: string;
  projectId: string;
  tokenUri: string;
  fetch: typeof fetch;
}

export class FcmError extends Error {
  constructor(
    readonly status: number,
    /** The device token is no longer valid: the app was uninstalled or the token rotated. */
    readonly unregistered: boolean,
  ) {
    super(`FCM answered ${status}`);
  }
}

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

let cached: { email: string; token: string; expires: number } | null = null;

/** Forgets the cached access token (tests). */
export function resetFcmToken(): void {
  cached = null;
}

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

async function accessToken(c: FcmConfig): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.email === c.clientEmail && cached.expires > now + 60) return cached.token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(
    JSON.stringify({ iss: c.clientEmail, scope: SCOPE, aud: c.tokenUri, iat: now, exp: now + 3600 }),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(c.privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${b64url(new Uint8Array(signature))}`;
  const r = await c.fetch(c.tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString(),
  });
  if (!r.ok) throw new FcmError(r.status, false);
  const body = (await r.json()) as { access_token?: string; expires_in?: number };
  if (!body.access_token) throw new FcmError(r.status, false);
  cached = { email: c.clientEmail, token: body.access_token, expires: now + (body.expires_in ?? 3600) };
  return body.access_token;
}

/** Sends a data-only message; throws [FcmError] on failure. */
export async function sendFcm(c: FcmConfig, deviceToken: string, data: Record<string, string>, priority: "HIGH" | "NORMAL" = "HIGH"): Promise<void> {
  const token = await accessToken(c);
  const r = await c.fetch(`https://fcm.googleapis.com/v1/projects/${c.projectId}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: { token: deviceToken, data, android: { priority, ttl: "86400s" } },
    }),
  });
  if (r.ok) return;
  if (r.status === 401) cached = null;
  let unregistered = r.status === 404;
  try {
    const err = (await r.json()) as { error?: { details?: { errorCode?: string }[] } };
    unregistered ||= (err.error?.details ?? []).some((d) => d.errorCode === "UNREGISTERED");
  } catch {
    // not JSON
  }
  throw new FcmError(r.status, unregistered);
}
