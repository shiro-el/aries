-- Everything the Relay stores. Envelopes are never stored.

-- One row per paired Host/phone. The pairing token itself is not stored,
-- only its SHA-256 (hex).
CREATE TABLE pairings (
  token_hash TEXT PRIMARY KEY,
  platform TEXT NOT NULL,
  device_token TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- Fixed-window counters: `push:<token_hash>` (per minute) and
-- `ip:<sha256 of client IP>` (new pairings per hour).
CREATE TABLE rate_limits (
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
);
