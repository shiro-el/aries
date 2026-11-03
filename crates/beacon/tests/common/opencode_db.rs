//! An OpenCode database for tests: the 1.19.5 schema of the tables the
//! Beacon reads, filled from the ticket-04 row fixtures
//! (`fixtures/agents/opencode/*.db-rows.json`) the way OpenCode writes
//! them (WAL, rows rewritten in place).

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};
use serde_json::Value;

const SCHEMA: &str = "
CREATE TABLE `session` (
  `id` text PRIMARY KEY, `project_id` text NOT NULL, `workspace_id` text, `parent_id` text,
  `slug` text NOT NULL, `directory` text NOT NULL, `path` text, `title` text NOT NULL,
  `version` text NOT NULL, `share_url` text, `summary_additions` integer,
  `summary_deletions` integer, `summary_files` integer, `summary_diffs` text, `metadata` text,
  `cost` real DEFAULT 0 NOT NULL, `tokens_input` integer DEFAULT 0 NOT NULL,
  `tokens_output` integer DEFAULT 0 NOT NULL, `tokens_reasoning` integer DEFAULT 0 NOT NULL,
  `tokens_cache_read` integer DEFAULT 0 NOT NULL, `tokens_cache_write` integer DEFAULT 0 NOT NULL,
  `revert` text, `permission` text, `agent` text, `model` text,
  `time_created` integer NOT NULL, `time_updated` integer NOT NULL,
  `time_compacting` integer, `time_archived` integer);
CREATE TABLE `message` (
  `id` text PRIMARY KEY, `session_id` text NOT NULL, `time_created` integer NOT NULL,
  `time_updated` integer NOT NULL, `data` text NOT NULL,
  FOREIGN KEY (`session_id`) REFERENCES `session`(`id`) ON DELETE CASCADE);
CREATE TABLE `part` (
  `id` text PRIMARY KEY, `message_id` text NOT NULL, `session_id` text NOT NULL,
  `time_created` integer NOT NULL, `time_updated` integer NOT NULL, `data` text NOT NULL,
  FOREIGN KEY (`message_id`) REFERENCES `message`(`id`) ON DELETE CASCADE);
CREATE INDEX `message_session_time_created_id_idx` ON `message` (`session_id`,`time_created`,`id`);
CREATE INDEX `part_message_id_id_idx` ON `part` (`message_id`,`id`);
CREATE INDEX `part_session_idx` ON `part` (`session_id`);
CREATE TABLE `credential` (`id` text PRIMARY KEY, `secret` text);
INSERT INTO `credential` VALUES ('c1', 'not-for-the-beacon');
";

pub fn fixture(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../fixtures/agents/opencode")
        .join(format!("{name}.db-rows.json"));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

/// `<data home>/opencode/opencode.db`.
pub fn db_path(data_home: &Path) -> PathBuf {
    data_home.join("opencode/opencode.db")
}

pub struct Db {
    pub path: PathBuf,
    conn: Connection,
}

impl Db {
    pub fn create(path: &Path) -> Self {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let conn = Connection::open(path).unwrap();
        conn.pragma_update(None, "journal_mode", "wal").unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        Self {
            path: path.to_owned(),
            conn,
        }
    }

    pub fn session(&self, s: &Value) {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO session (id, project_id, slug, directory, title, version, \
                 time_created, time_updated) VALUES (?1, 'global', ?2, ?3, ?4, '1.19.5', ?5, ?6)",
                params![
                    s["id"].as_str().unwrap(),
                    s["slug"].as_str().unwrap_or("s"),
                    s["directory"].as_str().unwrap_or("/w"),
                    s["title"].as_str().unwrap_or("t"),
                    s["time_created"].as_i64().unwrap(),
                    s["time_updated"].as_i64().unwrap(),
                ],
            )
            .unwrap();
    }

    pub fn message(&self, m: &Value) {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO message VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    m["id"].as_str().unwrap(),
                    m["session_id"].as_str().unwrap(),
                    m["time_created"].as_i64().unwrap(),
                    m["time_updated"].as_i64().unwrap(),
                    m["data"].to_string(),
                ],
            )
            .unwrap();
    }

    pub fn part(&self, p: &Value) {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO part VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    p["id"].as_str().unwrap(),
                    p["message_id"].as_str().unwrap(),
                    p["session_id"].as_str().unwrap(),
                    p["time_created"].as_i64().unwrap(),
                    p["time_updated"].as_i64().unwrap(),
                    p["data"].to_string(),
                ],
            )
            .unwrap();
    }

    /// Writes a whole fixture: the session, then each message followed by
    /// its parts.
    pub fn load(&self, f: &Value) {
        self.session(&f["session"]);
        for m in f["messages"].as_array().unwrap() {
            self.message(m);
            for p in parts_of(f, m) {
                self.part(&p);
            }
        }
    }
}

/// The fixture's parts of message `m`, in id order.
pub fn parts_of(f: &Value, m: &Value) -> Vec<Value> {
    f["parts"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|p| p["message_id"] == m["id"])
        .cloned()
        .collect()
}

/// The fixture row with this id suffix (ids are long; tests name the end).
pub fn row(f: &Value, table: &str, suffix: &str) -> Value {
    f[table]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["id"].as_str().unwrap().ends_with(suffix))
        .unwrap_or_else(|| panic!("no {table} row *{suffix}"))
        .clone()
}
