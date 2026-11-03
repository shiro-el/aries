# Running your own Relay

The Relay delivers push notifications from a Host's Beacon to the phone. It
only forwards end-to-end encrypted envelopes: it cannot read them, and it
stores only pairing token hashes, device push tokens and rate limit counters
(never an envelope, never a request body in its logs). You can still run your
own instead of the one your Aries build uses, and choose it per Host in the
app.

It is a Cloudflare Worker with a D1 database (`src/`, `migrations/`).

## What you need

- A Cloudflare account (the Workers free plan is enough) and an API token
  that can edit Workers and D1.
- **The Firebase project of the Aries app you install.** The Relay sends
  through Firebase Cloud Messaging, and FCM only delivers to an app with a
  service account key of that app's own Firebase project. So a self-hosted
  Relay works with:
  - an Aries build you make yourself, with your own Firebase project
    (`android/app/google-services.json` for the client
    `io.github.shiroel.aries`) and a service account key of that project; or
  - the official build, only if you have its Firebase service account key
    (normally you do not).
- Node.js 22+ and this repository.

## Deploy

```sh
cd relay
npm ci --legacy-peer-deps

export CLOUDFLARE_ACCOUNT_ID=...          # your account
export CLOUDFLARE_API_TOKEN=...           # Workers + D1 edit
npx wrangler d1 create aries-relay        # prints the database id
export ARIES_CF_D1_NAME=aries-relay
export ARIES_CF_D1_ID=<database id>
export ARIES_FCM_SERVICE_ACCOUNT_EMAIL=<client_email of the service account key>

node scripts/deploy-config.mjs            # writes wrangler.deploy.jsonc (not committed)
npx wrangler d1 migrations apply "$ARIES_CF_D1_NAME" --remote -c wrangler.deploy.jsonc
npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON -c wrangler.deploy.jsonc < service-account.json
npx wrangler deploy -c wrangler.deploy.jsonc
```

`wrangler deploy` prints the Worker's URL, for example
`https://aries-relay.<you>.workers.dev`. A custom domain (Cloudflare dashboard,
Workers > your Worker > Domains) works the same way. The Relay must be
reachable over `https://` from both the phone and the Host.

Keep the service account key file out of the repository; delete your copy once
it is stored as the secret.

Limits are Worker variables in `wrangler.jsonc` (`vars`):
`PUSH_LIMIT_PER_MINUTE` (pushes per pairing, default 30) and
`PAIR_LIMIT_PER_HOUR` (new pairings per client IP, default 20).

## Check it

```sh
curl -i -X POST https://<your relay>/v1/pairings -H 'Content-Type: application/json' -d '{}'
```

An Aries Relay answers `400` with `{"error":"bad_request"}`; `404` means the
URL is not the Relay. A missing FCM key shows later, when the test
notification is sent: the Relay answers `503` `{"error":"fcm_not_configured"}`
(set it with `wrangler secret put FCM_SERVICE_ACCOUNT_JSON`).

## Use it in the app

Open the Host, then in **Push notifications**:

- push off: put your Relay's URL in **Relay URL** and turn push on;
- push on: **Change Relay**, enter the URL, **Use this Relay**.

Aries registers the phone on the new Relay, re-pairs the Beacon with it,
then deletes the pairing on the old Relay and sends a test notification
through the new one. If any step before that fails, the app says which step
and why (an invalid URL, no Relay at that URL, a rate limit, ...) and the old
Relay keeps working. A failed test notification (for example FCM not set up
on the Relay) leaves push on with the new Relay and shows the Relay's answer;
fix the Relay and send another test. If the old Relay cannot be reached, its
pairing is deleted later, when the app next starts.

The URL must be `https://`. `http://` is accepted only for the device's own
address (`localhost`, `127.0.0.1`, `[::1]`), for a Relay run with
`npx wrangler dev` during development.

## Update or remove it

Pull the repository, then `npx wrangler d1 migrations apply ... --remote` and
`npx wrangler deploy` as above. Before deleting your Relay, switch each Host
to another Relay (or turn its push off) so the pairings are deleted first.
