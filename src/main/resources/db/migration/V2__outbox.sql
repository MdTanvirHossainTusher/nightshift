-- ============================================================================
-- Nightshift: Transactional Outbox Pattern Schema
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_event (
    id           uuid         PRIMARY KEY,
    type         varchar(64)  NOT NULL,
    payload      text         NOT NULL,
    status       varchar(16)  NOT NULL,   -- PENDING | SENT | FAILED
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    processed_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_outbox_event_status ON outbox_event (status, created_at);