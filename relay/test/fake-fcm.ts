/**
 * Stands in for Google OAuth and FCM HTTP v1 (the only fake at seam 2). It
 * checks the service account JWT's signature, hands out access tokens and
 * records every message sent.
 */

export interface SentMessage {
  projectId: string;
  accessToken: string;
  message: {
    token: string;
    data: Record<string, string>;
    android?: { priority?: string; ttl?: string };
  };
}

function pem(der: ArrayBuffer): string {
  let s = "";
  for (const b of new Uint8Array(der)) s += String.fromCharCode(b);
  const body = btoa(s).replace(/(.{64})/g, "$1\n");
  return `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`;
}

function b64urlDecode(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export class FakeFcm {
  readonly sent: SentMessage[] = [];
  tokensIssued = 0;
  /** The next send answers with this status and body instead of 200. */
  failNext: { status: number; body: unknown } | null = null;
  private publicKey!: CryptoKey;
  serviceAccountJson!: string;
  readonly email = "relay@aries-test.iam.gserviceaccount.com";

  static async create(): Promise<FakeFcm> {
    const f = new FakeFcm();
    const pair = (await crypto.subtle.generateKey(
      { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["sign", "verify"],
    )) as CryptoKeyPair;
    f.publicKey = pair.publicKey;
    const der = (await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer;
    f.serviceAccountJson = JSON.stringify({
      type: "service_account",
      project_id: "aries-test",
      client_email: f.email,
      private_key: pem(der),
      token_uri: "https://oauth2.googleapis.com/token",
    });
    return f;
  }

  readonly fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const req = new Request(input, init);
    const url = new URL(req.url);
    if (url.href === "https://oauth2.googleapis.com/token") return this.token(req);
    const m = /^\/v1\/projects\/([^/]+)\/messages:send$/.exec(url.pathname);
    if (url.host === "fcm.googleapis.com" && m) return this.send(req, m[1]);
    return new Response("unexpected request", { status: 599 });
  };

  private async token(req: Request): Promise<Response> {
    const form = new URLSearchParams(new TextDecoder().decode(await req.arrayBuffer()));
    const [h, c, s] = (form.get("assertion") ?? "").split(".");
    const ok = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      this.publicKey,
      b64urlDecode(s ?? ""),
      new TextEncoder().encode(`${h}.${c}`),
    );
    const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(c ?? "")));
    if (!ok || claims.iss !== this.email || form.get("grant_type") !== "urn:ietf:params:oauth:grant-type:jwt-bearer") {
      return new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 });
    }
    this.tokensIssued++;
    return Response.json({ access_token: `access-${this.tokensIssued}`, expires_in: 3600 });
  }

  private async send(req: Request, projectId: string): Promise<Response> {
    const accessToken = (req.headers.get("authorization") ?? "").replace(/^Bearer /, "");
    if (!accessToken.startsWith("access-")) return new Response("{}", { status: 401 });
    if (this.failNext) {
      const f = this.failNext;
      this.failNext = null;
      return Response.json(f.body, { status: f.status });
    }
    const body = (await req.json()) as { message: SentMessage["message"] };
    this.sent.push({ projectId, accessToken, message: body.message });
    return Response.json({ name: `projects/${projectId}/messages/${this.sent.length}` });
  }
}
