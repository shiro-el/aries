// Writes wrangler.deploy.jsonc: wrangler.jsonc with the real D1 database and
// the FCM service account email. Reads ARIES_CF_D1_NAME, ARIES_CF_D1_ID and
// ARIES_FCM_SERVICE_ACCOUNT_EMAIL from the environment (locally:
// ~/.config/aries/aries-mvp.env). Wrangler itself reads CLOUDFLARE_ACCOUNT_ID
// and CLOUDFLARE_API_TOKEN. The FCM key goes in as a secret, never here:
//   npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON -c wrangler.deploy.jsonc < key.json
import { readFileSync, writeFileSync } from "node:fs";

const need = (name) => {
  const v = process.env[name];
  if (!v) {
    console.error(`${name} is not set`);
    process.exit(1);
  }
  return v;
};

const text = readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8");
// wrangler.jsonc has only whole-line // comments.
const config = JSON.parse(text.replace(/^\s*\/\/.*$/gm, ""));
config.d1_databases[0].database_name = need("ARIES_CF_D1_NAME");
config.d1_databases[0].database_id = need("ARIES_CF_D1_ID");
config.vars.FCM_SERVICE_ACCOUNT_EMAIL = need("ARIES_FCM_SERVICE_ACCOUNT_EMAIL");
writeFileSync(new URL("../wrangler.deploy.jsonc", import.meta.url), `${JSON.stringify(config, null, 2)}\n`);
console.log("wrote wrangler.deploy.jsonc");
