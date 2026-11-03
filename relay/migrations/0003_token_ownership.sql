-- Existing registrations remain active; only new/changed tokens need proof.
ALTER TABLE pairings ADD COLUMN verified INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pairings ADD COLUMN verification_id TEXT;
CREATE INDEX pairings_unverified_expiry ON pairings(created_at) WHERE verified = 0;

-- Proofs are random 256-bit capabilities, stored only as SHA-256 hashes.
-- A rotation keeps delivering to the old verified device until confirmed.
CREATE TABLE pairing_challenges (
  verification_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE REFERENCES pairings(token_hash) ON DELETE CASCADE,
  device_token TEXT NOT NULL,
  challenge_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX pairing_challenges_expiry ON pairing_challenges(expires_at);
