-- Nightshift core schema.
--
-- Postgres only. The `standalone` profile runs on H2 with Flyway disabled and
-- Hibernate generating the schema, so judges can boot the app with no database;
-- every real deployment uses these migrations with ddl-auto=validate.
--
-- Migrations are append-only: once a V-file is merged it is never edited.

CREATE TABLE log_source (
    id              uuid        PRIMARY KEY,
    name            varchar(64) NOT NULL UNIQUE,
    root_path       text        NOT NULL,
    file_glob       varchar(128) NOT NULL DEFAULT '**/*.log',
    service_name    varchar(64),
    -- Repo the stack traces in this source point at, so the code locator knows
    -- which working copy to resolve frames against.
    target_repo     varchar(128),
    enabled         boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE log_source IS 'A folder of rotated log files that Nightshift watches.';

-- ── Scan runs ────────────────────────────────────────────────────────────────

CREATE TABLE scan_run (
    id                 uuid        PRIMARY KEY,
    trigger_source     varchar(16) NOT NULL,   -- SCHEDULE | MANUAL | API | MCP
    status             varchar(16) NOT NULL,   -- RUNNING | COMPLETED | FAILED
    started_at         timestamptz NOT NULL,
    finished_at        timestamptz,
    files_seen         integer     NOT NULL DEFAULT 0,
    files_with_new_data integer    NOT NULL DEFAULT 0,
    bytes_read         bigint      NOT NULL DEFAULT 0,
    lines_parsed       bigint      NOT NULL DEFAULT 0,
    events_matched     integer     NOT NULL DEFAULT 0,
    incidents_new      integer     NOT NULL DEFAULT 0,
    incidents_updated  integer     NOT NULL DEFAULT 0,
    patches_proposed   integer     NOT NULL DEFAULT 0,
    prs_opened         integer     NOT NULL DEFAULT 0,
    error_message      text
);

CREATE INDEX idx_scan_run_started_at ON scan_run (started_at DESC);

-- Only one run may be RUNNING at a time. A partial unique index on `status` makes
-- the database enforce that: among rows where status = 'RUNNING', status must be
-- unique, so there can only ever be one. Better here than a service-level check,
-- which races when the 02:00 schedule and a manual trigger fire together.
CREATE UNIQUE INDEX uq_scan_run_single_active
    ON scan_run (status) WHERE status = 'RUNNING';

-- ── The "already checked" ledger ──────────────────────────────────────────────
--
-- This table is what makes a scan incremental. A rotated log file is append-only,
-- so the pair (size_bytes, byte_offset) is enough to read only the bytes added
-- since the last run. content_hash covers the first 4 KiB: if it changes while
-- the path stays the same, the file was replaced by rotation and the offset is
-- reset instead of silently skipping the new file.

CREATE TABLE scanned_file (
    id               uuid        PRIMARY KEY,
    log_source_id    uuid        NOT NULL REFERENCES log_source (id) ON DELETE CASCADE,
    relative_path    text        NOT NULL,
    size_bytes       bigint      NOT NULL,
    byte_offset      bigint      NOT NULL DEFAULT 0,
    content_hash     varchar(64) NOT NULL,
    last_modified_at timestamptz NOT NULL,
    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_scanned_at  timestamptz NOT NULL,
    last_scan_run_id uuid        REFERENCES scan_run (id) ON DELETE SET NULL,
    CONSTRAINT uq_scanned_file_path UNIQUE (log_source_id, relative_path)
);

CREATE INDEX idx_scanned_file_last_scanned ON scanned_file (last_scanned_at);

-- ── Incidents ────────────────────────────────────────────────────────────────
--
-- One row per distinct problem, not per log line. The fingerprint collapses the
-- same failure across seven days of files into a single incident, which is the
-- difference between 40 000 log lines and the handful of PRs a human can review.

CREATE TABLE incident (
    id                  uuid         PRIMARY KEY,
    fingerprint         varchar(64)  NOT NULL UNIQUE,
    title               varchar(256) NOT NULL,
    service_name        varchar(64),
    logger_name         varchar(256),
    log_level            varchar(8)   NOT NULL,   -- WARN | ERROR | FATAL
    exception_type       varchar(256),
    normalized_message   text         NOT NULL,
    sample_message       text,
    sample_stacktrace    text,

    occurrence_count     integer      NOT NULL DEFAULT 0,
    first_seen_at        timestamptz  NOT NULL,
    last_seen_at         timestamptz  NOT NULL,

    status               varchar(24)  NOT NULL,   -- see IncidentStatus
    severity             varchar(16),             -- BLOCKER | CRITICAL | MAJOR | MINOR | TRIVIAL
    severity_rationale   text,
    category             varchar(48),
    root_cause           text,                    -- why the problem arises
    future_impact        text,                    -- what it costs if left unfixed
    recommended_action   text,
    confidence           numeric(3,2),            -- 0.00 .. 1.00
    triaged_at           timestamptz,
    triage_provider      varchar(32),
    triage_model         varchar(64),

    muted                boolean      NOT NULL DEFAULT false,
    mute_reason          text,
    assignee_email       varchar(256),
    assignee_handle      varchar(64),

    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_status       ON incident (status);
CREATE INDEX idx_incident_last_seen    ON incident (last_seen_at DESC);
CREATE INDEX idx_incident_severity     ON incident (severity) WHERE muted = false;

CREATE TABLE incident_occurrence (
    id           uuid        PRIMARY KEY,
    incident_id  uuid        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    scan_run_id  uuid        REFERENCES scan_run (id) ON DELETE SET NULL,
    occurred_at  timestamptz NOT NULL,
    log_file     text        NOT NULL,
    line_number  integer,
    trace_id     varchar(64),
    thread_name  varchar(128),
    raw_line     text        NOT NULL
);

-- Occurrences are sampled (see nightshift.scan.max-occurrence-samples): the count
-- lives on the incident, and only a bounded set of raw lines is kept as evidence.
CREATE INDEX idx_occurrence_incident ON incident_occurrence (incident_id, occurred_at DESC);

-- ── Where the code is ────────────────────────────────────────────────────────

CREATE TABLE code_location (
    id               uuid        PRIMARY KEY,
    incident_id      uuid        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    target_repo      varchar(128) NOT NULL,
    file_path        text        NOT NULL,
    start_line       integer,
    end_line         integer,
    frame_signature  text,
    -- Resolved from a real stack frame (HIGH) or inferred from the logger name
    -- when the event had no stack trace (LOW). The fix agent is only allowed to
    -- patch HIGH/MEDIUM locations.
    confidence       varchar(8)  NOT NULL,
    snippet          text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_code_location_incident ON code_location (incident_id);

-- ── Proposed fixes ───────────────────────────────────────────────────────────

CREATE TABLE patch_proposal (
    id               uuid        PRIMARY KEY,
    incident_id      uuid        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    status           varchar(24) NOT NULL,   -- DRAFT | VERIFIED | REJECTED | PUBLISHED
    unified_diff     text,
    rationale        text,
    test_plan        text,
    files_changed    integer     NOT NULL DEFAULT 0,
    lines_added      integer     NOT NULL DEFAULT 0,
    lines_removed    integer     NOT NULL DEFAULT 0,
    provider         varchar(32),
    model            varchar(64),
    -- The verifier is a second agent that re-reads the diff against the located
    -- source and must find evidence for the claim. PASS | FAIL | SKIPPED.
    verifier_verdict varchar(16),
    verifier_notes   text,
    rejection_code   varchar(48),
    attempt          integer     NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_patch_incident ON patch_proposal (incident_id, created_at DESC);

CREATE TABLE pull_request (
    id                uuid         PRIMARY KEY,
    incident_id       uuid         NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    patch_proposal_id uuid         REFERENCES patch_proposal (id) ON DELETE SET NULL,
    provider          varchar(16)  NOT NULL DEFAULT 'GITHUB',
    repo_full_name    varchar(160) NOT NULL,
    branch_name       varchar(200) NOT NULL,
    base_branch       varchar(120) NOT NULL DEFAULT 'main',
    pr_number         integer,
    pr_url            text,
    state             varchar(16)  NOT NULL,   -- OPEN | MERGED | CLOSED | FAILED
    assignee_handle   varchar(64),
    opened_at         timestamptz,
    closed_at         timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    -- One open PR per incident per branch; a re-scan must not re-open the same fix.
    CONSTRAINT uq_pull_request_branch UNIQUE (repo_full_name, branch_name)
);

CREATE INDEX idx_pull_request_incident ON pull_request (incident_id);

-- ── Notifications ────────────────────────────────────────────────────────────

CREATE TABLE notification (
    id               uuid         PRIMARY KEY,
    incident_id      uuid         REFERENCES incident (id) ON DELETE CASCADE,
    pull_request_id  uuid         REFERENCES pull_request (id) ON DELETE SET NULL,
    channel          varchar(16)  NOT NULL,   -- EMAIL | WEBHOOK
    recipient        varchar(256) NOT NULL,
    subject          varchar(512),
    body             text,
    status           varchar(16)  NOT NULL,   -- PENDING | SENT | FAILED
    attempts         integer      NOT NULL DEFAULT 0,
    last_error       text,
    sent_at          timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_status ON notification (status, created_at);

-- ── Agent trajectory ─────────────────────────────────────────────────────────
--
-- Every model call and every tool call is recorded. This is the audit trail that
-- answers "why did the agent claim that?" after the fact, and it is what the
-- evaluation harness replays to compare pipeline variants.

CREATE TABLE agent_step (
    id            uuid        PRIMARY KEY,
    scan_run_id   uuid        REFERENCES scan_run (id) ON DELETE CASCADE,
    incident_id   uuid        REFERENCES incident (id) ON DELETE CASCADE,
    agent_role    varchar(24) NOT NULL,   -- TRIAGE | LOCATE | FIX | VERIFY | PUBLISH
    step_index    integer     NOT NULL,
    step_type     varchar(16) NOT NULL,   -- MODEL | TOOL
    tool_name     varchar(64),
    provider      varchar(32),
    model         varchar(64),
    input_summary text,
    output_summary text,
    tokens_in     integer,
    tokens_out    integer,
    latency_ms    integer,
    status        varchar(16) NOT NULL,   -- OK | ERROR
    error_message text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_step_incident ON agent_step (incident_id, step_index);
CREATE INDEX idx_agent_step_run      ON agent_step (scan_run_id, created_at);
