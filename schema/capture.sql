-- schema/capture.sql
-- Source of truth for the SPIKE-01 capture table.
-- Consumed verbatim by GRDB (iOS) and SQLDelight (Android).
-- Postgres mirror lives at supabase/migrations/0001_capture_items.sql.
-- Per ADR-002: capture_items is append-only, no conflicts possible.
-- Idempotency for PowerSync replay across retries: (device_id, client_seq).

CREATE TABLE IF NOT EXISTS capture_items (
    id           TEXT    PRIMARY KEY,        -- ULID, lex-sortable
    raw_text     TEXT    NOT NULL,           -- as captured, never mutated
    captured_at  REAL    NOT NULL,           -- unix epoch seconds, fractional
    device_id    TEXT    NOT NULL,           -- stable per install
    client_seq   INTEGER NOT NULL,           -- monotonic per device
    source       TEXT    NOT NULL            -- voice|email|plugin|hotkey|drag|post_call|sheet
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_capture_items_device_seq
    ON capture_items (device_id, client_seq);

CREATE INDEX IF NOT EXISTS ix_capture_items_captured_at
    ON capture_items (captured_at);
