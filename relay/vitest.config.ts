import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Seam 2: the Relay's HTTP API inside the Workers runtime (Miniflare), with
// a real local D1. Only FCM is fake (test/fake-fcm.ts).
export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.jsonc" },
        miniflare: { bindings: { TEST_MIGRATIONS: migrations } },
      }),
    ],
    // Crypto and real D1 transactions can exceed 5s on shared build hosts.
    test: { setupFiles: ["./test/apply-migrations.ts"], testTimeout: 30_000 },
  };
});
