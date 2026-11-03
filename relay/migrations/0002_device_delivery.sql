-- Transport replay IDs only: no envelope or plaintext event metadata.
-- The FCM device token and envelope are both represented by SHA-256 hashes.
CREATE TABLE push_events (
  device_hash TEXT NOT NULL,
  event_hash TEXT NOT NULL,
  claim_id TEXT NOT NULL,
  accepted INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (device_hash, event_hash)
);
CREATE INDEX push_events_expiry ON push_events (expires_at);
