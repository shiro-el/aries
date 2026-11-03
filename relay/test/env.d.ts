declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    PUSH_LIMIT_PER_MINUTE: string;
    PAIR_LIMIT_PER_HOUR: string;
    TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
  }
}
