# Nightshift — Implementation Plan

## Overview

Nightshift is a scheduled agentic pipeline that reads application logs overnight, groups
them into distinct incidents, diagnoses each against real source code, writes a patch, opens
a GitHub pull request per incident, and emails the owning developer. The goal is to convert
an unread log folder into a reviewable PR queue before the next working day starts.

The project is a **Java 21 / Spring Boot 3.4.3** monolith (one jar, one container, one URL).
The LLM is only used in three of nine pipeline steps; everything else is deterministic and
fully testable.

### What is Already Done

| Path | Status |
| --- | --- |
| `build.gradle`, `settings.gradle`, `gradle/` | ✅ done — Java 21, Boot 3.4.3, JGit, Flyway, springdoc, MapStruct, Lombok, JaCoCo |
| `src/main/java/com/nightshift/payload/common/**` | ✅ done — `ApiResponse`, `ErrorResponse`, `ErrorDetail`, `Pagination`, `PageResult`, `ResponseMetadata`, `ResponseBuilder` |
| `src/main/java/com/nightshift/exception/**` | ✅ done — full `AppException` hierarchy + `GlobalExceptionHandler` |
| `src/main/java/com/nightshift/constant/code/**` | ✅ done — `ErrorCodes`, `SuccessCodes` |
| `src/main/resources/db/migration/V1__core_schema.sql` | ✅ done — 9 tables with indexes and comments |
| `NightshiftApplication.java` | ✅ done |
| `Dockerfile`, `compose.yml`, `.github/workflows/**`, `release-please-config.json` | ✅ done |
| `demo/generate-logs.mjs`, `demo/logs/**` (21 files, 23 070 lines), `demo/target-repo/**` | ✅ done |
| `config/assignment-rules.yml`, `config/redaction.yml` | ✅ done |

### What Needs to Be Built (in order)

Tasks 1–13 are the product. Tasks 14–16 are the differentiators. Tasks 17–18 are submission.

---

## Task 1 — Entities, Enums, and Repositories

**Status:** `[x] done`

**Intent:** Map all 9 tables in `V1__core_schema.sql` to JPA entities with UUID v7 primary keys
and Spring Data repositories, so `bootRun --ddl-auto=validate` starts clean on Postgres.

**Expected Outcomes:**
- All 9 entities exist under `model/entity/`
- All enums exist under `model/enums/`
- All repositories exist under `repository/`
- `./gradlew bootRun` starts and Hibernate validates against `V1` with no schema mismatches

**Todo List:**
1. Create enums: `TriggerSource`, `ScanStatus`, `IncidentStatus`, `Severity`, `Category`,
   `ConfidenceLevel`, `PatchStatus`, `VerifierVerdict`, `PrState`, `NotificationChannel`,
   `NotificationStatus`, `AgentRole`, `StepType`
2. Create entities: `LogSource`, `ScanRun`, `ScannedFile`, `Incident`, `IncidentOccurrence`,
   `CodeLocation`, `PatchProposal`, `PullRequest`, `Notification`, `AgentStep`
3. Use `@UuidGenerator(style = TIME)` (hypersistence-utils) for UUID v7 PKs
4. Add `@UpdateTimestamp` on `incident.updated_at`; `@CreationTimestamp` on `created_at` fields
5. Add `@Column(columnDefinition = "numeric(3,2)")` on `incident.confidence`
6. Create repositories: one Spring Data `JpaRepository` per entity, plus a custom method for
   `ScannedFileRepository.findByLogSourceIdAndRelativePath` and
   `ScanRunRepository.existsByStatus("RUNNING")`
7. Verify `./gradlew test` is green (unit tests only, no Docker needed for this task)

**Relevant Context:**
- Schema: `nightshift/src/main/resources/db/migration/V1__core_schema.sql`
- UUID v7 via `com.github.f4b6a3:uuid-creator` + `io.hypersistence:hypersistence-utils`
- Layout convention: `model/entity/`, `model/enums/`, `repository/`
- Lombok `@RequiredArgsConstructor` / `@Builder` on entities
- `standalone` profile uses H2 + `ddl-auto=create`; enums must have `@Enumerated(EnumType.STRING)`

---

## Task 2 — application.yaml, Standalone Profile, NightshiftProperties, Actuator, Springdoc

**Status:** `[x] done`

**Intent:** Configure the application so it boots on Postgres with the full stack, and on H2
with no Docker (the `standalone` profile). Expose actuator health and prometheus endpoints.
Wire springdoc to show OpenAPI at `/swagger-ui.html`.

**Expected Outcomes:**
- `./gradlew bootRun --args='--spring.profiles.active=standalone'` starts with no Docker, no DB
- `http://localhost:8080/actuator/health` returns `{"status":"UP"}`
- `http://localhost:8080/swagger-ui.html` opens the OpenAPI UI
- `NightshiftProperties` is a `@ConfigurationProperties` bean covering all `nightshift.*` keys

**Todo List:**
1. Create `src/main/resources/application.yaml` — Postgres datasource, Flyway on, springdoc,
   actuator ports (app: 8080, management: 9091), Jackson snake_case + non-null, async executor
2. Create `src/main/resources/application-standalone.yaml` — H2 in-memory, Flyway off,
   `ddl-auto: create`, mail to noop, messaging transport local, llm provider heuristic
3. Create `src/main/resources/application-docker.yaml` — reads env vars from `compose.yml`,
   log root `/app/logs`, workspace `/app/workspace`
4. Create `NightshiftProperties` under `config/properties/` — bind `nightshift.llm.*`,
   `nightshift.scan.*`, `nightshift.patch.*`, `nightshift.git.*`, `nightshift.messaging.*`,
   `nightshift.log-root`, `nightshift.workspace`
5. Add `management.endpoints.web.exposure.include = health, prometheus, info` on port 9091
6. Add springdoc config: `api-docs.path=/api-docs`, scan packages

**Relevant Context:**
- `compose.yml` env vars reveal all required property keys
- `config/redaction.yml` and `config/assignment-rules.yml` are read at runtime, not Spring-bound
- `NightshiftApplication` already has `@ConfigurationPropertiesScan`
- Wire format: Jackson `SNAKE_CASE`, `default-property-inclusion: non_null`, `Instant` / ISO-8601

---

## Task 3 — Incremental Reader, Parser, Fingerprinter, Scanned-File Checkpointing

**Status:** `[x] done`

**Intent:** Build the deterministic log-ingestion pipeline (steps 1–3 in the nine-step diagram).
Read only new bytes past the stored offset, parse logback-format lines into `LogEvent` objects,
join multi-line stack traces, normalise variable tokens (UUIDs, numbers, IDs), and hash the
result to produce an incident fingerprint. Store the byte offset in `scanned_file` so a second
scan over unchanged logs produces zero new incidents.

**Expected Outcomes:**
- A unit test proves a second scan over the same demo logs finds 0 new incidents
- Defect #3 (WARN-only, no exception) is parsed (message-only matching works)
- Defect #8 (same NPE from a second service) produces the same fingerprint as defect #2
- `scanned_file.byte_offset` advances after each scan

**Todo List:**
1. Create `service/scan/LogEventParser` — reads logback-format lines
   (`%d{...} [%thread] %-5level %logger --- %msg%n`), joins continuation lines and stack frames
2. Create `service/scan/LogEvent` value type — level, logger, timestamp, message, stacktrace,
   threadName, traceId (extracted from MDC prefix if present)
3. Create `service/scan/IncidentFingerprinter` — normalise uuids / numeric ids / hex strings
   in the message; hash `exceptionType + ":" + top-application-frame + ":" + normalisedMessage`
   using SHA-256 truncated to 16 hex chars
4. Create `service/scan/IncrementalLogReader` — given a `LogSource`, walk files matching the
   glob, load `ScannedFile` records, seek to `byte_offset`, read and checksum the first 4 KiB;
   if the hash differs from `content_hash`, reset the offset (file was rotated); write new
   offset and hash back via `ScannedFileRepository` in the same transaction
5. Create `service/scan/ScanService` / `ScanServiceImpl` — orchestrate reader → parser →
   fingerprinter → upsert `Incident` + `IncidentOccurrence` rows; update `ScanRun` counters
6. Write unit test: scan demo/logs twice, assert `incidents_new = 0` on the second pass

**Relevant Context:**
- Demo logs are logback format; see `demo/logs/farmer-service/*.log` for the real pattern
- Fingerprint deduplication is the key invariant: `incident.fingerprint` has `UNIQUE` index
- `scanned_file.content_hash` covers first 4 KiB (see comment in `V1__core_schema.sql`)
- Application-package filter for frame resolution: `nightshift.locator.application-packages`
- `SecretMasker` (to be built in Task 5) must be called over any log line before it is stored

---

## Task 4 — LlmClient SPI, Registry, and Heuristic Client

**Status:** `[ ] pending`

**Intent:** Define the `LlmClient` interface and `LlmClientRegistry`, then implement the
`heuristic` client — a fully offline, rule-based provider that runs the complete pipeline
including PR creation with zero API keys. This is the demo insurance policy and must be built
before any real provider.

**Expected Outcomes:**
- `LlmClientRegistry.resolve("heuristic")` returns the heuristic client
- When `nightshift.llm.provider = heuristic`, the full pipeline (triage → fix → verify → publish)
  runs without any network call
- The heuristic client produces a triage result matching all 8 demo defects at the right severity
  (within the `severity_tolerance` from `expected-findings.json`)

**Todo List:**
1. Create `service/llm/LlmClient` interface with `provider()`, `complete(LlmRequest)`, `isAvailable()`
2. Create `service/llm/LlmRequest` and `service/llm/LlmResponse` value types
   (systemPrompt, userPrompt, jsonSchemaHint → responseText, tokensIn, tokensOut)
3. Create `service/llm/LlmClientRegistry` — reads `nightshift.llm.provider`, injects all
   `LlmClient` beans, picks the configured one; falls back to `heuristic` when key is absent
4. Create `service/llm/HeuristicLlmClient` — offline, exception-taxonomy-based:
   - Maps `SQLTransientConnectionException` → `CRITICAL / RESOURCE_LEAK`
   - Maps `NullPointerException` → `MAJOR / NULL_DEREFERENCE`
   - Maps slow-query warn pattern → `MAJOR / PERFORMANCE`
   - Maps rate-limit warn pattern → `CRITICAL / RETRY_STORM`
   - Maps deserialization exception → `MAJOR / DATA_LOSS`
   - Maps OptimisticLockException → `MINOR / CONCURRENCY` + `already_handled`
   - Maps deprecated config logger → `TRIVIAL / MAINTENANCE` + `framework_noise`
   - Returns template unified diffs for defects 1–5 from `demo/target-repo`
5. Write unit test asserting heuristic triage produces all 7 distinct incidents at the right severities

**Relevant Context:**
- `service/llm/` package is the right location (already created as empty directory)
- `demo/expected-findings.json` is the ground truth; all 8 defect patterns are documented there
- `LlmRequest` carries the system prompt path (under `src/main/resources/prompts/`) + user content
- Parse failures: retry once with a repair instruction, then record `LLM_RESPONSE_UNPARSEABLE`

---

## Task 5 — Triage Agent, prompts/triage.md, AgentStep Recording

**Status:** `[x] complete`

**Intent:** Build the triage agent (step 4 in the pipeline): take a parsed incident, pass it
to the configured LLM with the structured prompt, parse the JSON response, persist
severity/category/root_cause/future_impact/confidence, and record every model call as an
`agent_step` row. Also build `SecretMasker` here — it must run over all log excerpts before
they enter any prompt.

**Expected Outcomes:**
- 8 demo incidents get severity, category, root cause, and future_impact populated
- Every model call writes an `agent_step` row
- `SecretMasker` strips bearer tokens, JDBC passwords, etc. per `config/redaction.yml`
- Future impact for defect #1 mentions "pool" and "exhaust"

**Todo List:**
1. Create `src/main/resources/prompts/triage.md` — full system prompt including the
   severity rubric (BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL with definitions), weight-by-trend
   instruction, and the JSON output schema
2. Create `util/SecretMasker` — loads `config/redaction.yml` at startup, applies regex patterns
   to any string, drops lines matching `drop_lines_matching`
3. Create `service/agent/TriageAgent` / `TriageAgentImpl` — compose log evidence (up to 5
   sampled raw lines, masked), call `LlmClient.complete()`, parse the JSON response, update
   `Incident` fields, record `agent_step` rows (one MODEL step per call)
4. Create `AgentStepRecorder` utility — wraps every model/tool call and persists the
   `agent_step` row with latency, token counts, input/output summaries
5. Wire `SecretMasker` into `IncidentOccurrence` storage so raw lines are masked before write
6. Test: triage all 8 demo incidents with heuristic client; assert severity on each

**Relevant Context:**
- `service/agent/` package is the right location
- `agent_step.agent_role` values: `TRIAGE | LOCATE | FIX | VERIFY | PUBLISH`
- `agent_step.step_type`: `MODEL | TOOL`
- Severity rubric from PROMPT.md §5.1 must be verbatim in the prompt
- Parse failures → retry once with repair instruction → `LLM_RESPONSE_UNPARSEABLE`

---

## Task 6 — Code Locator over demo/target-repo

**Status:** `[x] done`

**Intent:** Implement the deterministic code locator (step 5): parse stack frames from the
incident, keep only application-package frames (filter by `nightshift.locator.application-packages`),
map `com.example.farmer.FarmerSyncService.pushPending(FarmerSyncService.java:147)` to the
actual file at `src/main/java/com/example/farmer/FarmerSyncService.java:147` in the
target repo, read ±40 lines as a snippet, and store a `code_location` row. No LLM.

**Expected Outcomes:**
- Every seeded defect (1–6) resolves to the correct file and line (matching `code_location.file`
  in `expected-findings.json`)
- Defect #3 (no stack trace) falls back to logger-name → class → file with `confidence = LOW`
- Defect #7 (framework logger) produces no `code_location` (logger package not in application packages)
- `LOW` confidence → no patch proposed

**Todo List:**
1. Create `service/scan/CodeLocatorService` — given `Incident` with a sample stacktrace:
   a. Split the stack trace into frames
   b. Filter frames by `nightshift.locator.application-packages` prefixes
   c. Map the top frame's class+method to a relative file path
   d. Walk the `LogSource.targetRepo` directory to find the file
   e. Read ±40 lines around the matched line number
   f. Persist `CodeLocation` with `confidence = HIGH`
2. Add fallback: if no stack trace, split `incident.loggerName` on `.`, treat the last two
   segments as `PackageName.ClassName`, resolve the file, set `confidence = LOW`
3. Write a unit test that resolves all 6 located defects from `demo/target-repo` by fingerprint

**Relevant Context:**
- `demo/target-repo/src/main/java/com/example/` has the actual Java source files
- Each source file contains `// NS_FRAME_*` marker comments at the relevant lines (visible in `expected-findings.json`)
- `code_location.confidence` is a `varchar(8)` in the DB: values are `HIGH`, `MEDIUM`, `LOW`
- `LOW` locations: diagnosed and reported, but never patched → `SOURCE_FILE_NOT_LOCATED` code

---

## Task 7 — Fix Agent, Patch Guards, git apply --check

**Status:** `[x] done`

**Intent:** Build the fix agent (step 6): given the triage result and the located source,
call the LLM for a unified diff, then apply all safety guards: allowed-paths check, max-files /
max-changed-lines budget, deny-list check (build files, migrations, CI, secrets, `.github/`),
and `git apply --check` via JGit. `LOW`-confidence locations never reach this agent.

**Expected Outcomes:**
- Valid unified diff produced for defects 1–5
- Defect #7 produces no diff (no `code_location`)
- A diff touching `build.gradle` is rejected with `PATCH_TOUCHES_DENIED_PATH`
- A diff exceeding 120 changed lines is rejected with `PATCH_TOO_LARGE`
- `git apply --check` failure produces `PATCH_DOES_NOT_APPLY`

**Todo List:**
1. Create `src/main/resources/prompts/fix.md` — system prompt with rules: minimal diff, no
   refactoring, no new dependencies, no reformatting, match surrounding style; if fix is larger
   than budget, return empty diff with explanation in `rationale`
2. Create `service/agent/FixAgent` / `FixAgentImpl`:
   a. Assert `code_location.confidence != LOW` (else throw `SOURCE_FILE_NOT_LOCATED`)
   b. Call LLM with triage + located source; parse `{unified_diff, rationale, test_plan, files}`
   c. Run patch guards (see below); reject with appropriate code on failure
   d. If `git apply --check` passes, persist `PatchProposal` with status `DRAFT`
3. Create `util/PatchGuard` — checks allowed-paths, deny-list, max-files, max-changed-lines
4. Use JGit `ApplyCommand` in dry-run mode for `git apply --check`
5. Record `agent_step` rows for the model call and each tool call (guard check, git check)

**Relevant Context:**
- `nightshift.patch.allowed-paths`, `max-files` (default 3), `max-changed-lines` (default 120)
- Deny list from PROMPT.md §4 rule 3: build files, `db/migration/**`, CI workflows, `Dockerfile`,
  secrets, `.github/**`
- `patch_proposal.status` values: `DRAFT | VERIFIED | REJECTED | PUBLISHED`
- `patch_proposal.rejection_code` maps to `ErrorCodes` constants

---

## Task 8 — Verifier Agent

**Status:** `[x] done`

**Intent:** Build the verifier (step 7): a deliberately adversarial second agent call that is
given the diff, the original source, and the triage claim and must find the reason to reject
it. An unsupported claim results in `REJECTED` status and `TRIAGED_PATCH_REJECTED` incident
state — that is a success, not a failure.

**Expected Outcomes:**
- A diff that cannot be grounded in the provided source gets `verdict = FAIL` and no PR opens
- `patch_proposal.status` set to `VERIFIED` on pass, `REJECTED` on fail
- `patch_proposal.verifier_verdict` and `verifier_notes` persisted
- Incident still appears on the dashboard with `TRIAGED_PATCH_REJECTED` status

**Todo List:**
1. Create `src/main/resources/prompts/verify.md` — adversarial framing: "find the reason to
   reject this"; output schema `{verdict, notes, unsupported_claims}`
2. Create `service/agent/VerifierAgent` / `VerifierAgentImpl`:
   a. Call LLM with diff + original source + triage claim
   b. Parse response; on `FAIL` set `patch_proposal.status = REJECTED` and
      `incident.status = TRIAGED_PATCH_REJECTED`, record `rejection_code`
   c. On `PASS` set `patch_proposal.status = VERIFIED`
3. Record `agent_step` rows for the model call
4. Unit test: feed a diff that contradicts the source and assert `FAIL` verdict

**Relevant Context:**
- A rejected patch is a feature, not a bug — say so in the prompt and in code comments
- `incident.status` values (from schema): must include `TRIAGED_PATCH_REJECTED`
- `verifier_verdict` is `varchar(16)`: values `PASS | FAIL | SKIPPED`

---

## Task 9 — Publisher: JGit Branch, Commit, Push + GitHub PR from PR_TEMPLATE.md

**Status:** `[x] done`

**Intent:** Build the publisher (step 8): create a branch `nightshift/fix-<severity>-<short-fingerprint>`
off the configured base, apply the verified patch, commit with a conventional message, push
via JGit, then open a GitHub PR via the REST API using the body template in `docs/PR_TEMPLATE.md`.

**Expected Outcomes:**
- A real PR opens on the configured scratch repo when `GITHUB_TOKEN` and `NIGHTSHIFT_GIT_REPO` are set
- PR body follows the 8-section template (what is wrong, severity, root cause, future impact,
  evidence, fix, test plan, provenance)
- Branch name: `nightshift/fix-<severity>-<fingerprint[:8]>`
- `pull_request` row is persisted with `state = OPEN`
- `uq_pull_request_branch` constraint prevents double-open

**Todo List:**
1. Create `docs/PR_TEMPLATE.md` — 8-section template as specified in PROMPT.md §5.5
2. Create `service/publish/PublisherService` / `PublisherServiceImpl`:
   a. Clone/open the target repo working copy from `nightshift.workspace`
   b. Create branch, apply patch using JGit `ApplyCommand`, commit
   c. Push to remote via JGit with the stored `GITHUB_TOKEN` credential
   d. Call GitHub REST API `POST /repos/{owner}/{repo}/pulls` with the rendered PR body
   e. Persist `PullRequest` row in same transaction as the outbox event (Task 10 dependency)
3. Create `util/PrBodyRenderer` — fills the `PR_TEMPLATE.md` template with incident data
4. Record `agent_step` rows for git operations and API call

**Relevant Context:**
- JGit: `org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r` is already in `build.gradle`
- Branch naming: `nightshift/fix-<lowercase-severity>-<fingerprint.substring(0,8)>`
- PR base branch: `nightshift.git.base-branch` (default `main`)
- GitHub REST endpoint: `https://api.github.com/repos/{owner}/{repo}/pulls`
- Token from `GITHUB_TOKEN` env var — never hardcoded

---

## Task 10 — Outbox Pattern + Email Notifier + Assignment Rules

**Status:** `[x] done`

**Intent:** Implement the transactional outbox: the `pull_request` row and an `outbox_event`
row commit atomically, the relay publishes after commit via `@TransactionalEventListener(AFTER_COMMIT)`,
and a `@Scheduled` sweeper picks up anything the immediate path missed using `FOR UPDATE SKIP LOCKED`.
The notifier reads `config/assignment-rules.yml`, resolves the assignee, and queues an email.

**Expected Outcomes:**
- PR row and outbox row commit in the same transaction
- Email is sent to MailHog (in `docker compose` mode) with subject `[Nightshift] <SEVERITY> · <title> · PR #<n>`
- If SMTP is down, the outbox sweeper retries with backoff and parks as `FAILED` after N attempts
- `nightshift.messaging.transport = kafka` publishes to topic `nightshift.notifications` instead

**Todo List:**
1. Create `service/outbox/OutboxEvent` entity and `OutboxEventRepository`
   (add `V2__outbox.sql` migration — a new table `outbox_event` with `id, type, payload, status, attempts`)
2. Create `service/outbox/OutboxRelay` — `@TransactionalEventListener(AFTER_COMMIT)` for the
   immediate path; `@Scheduled(fixedDelay = 30_000)` sweeper with `FOR UPDATE SKIP LOCKED`
3. Create `service/outbox/NotificationService` — loads `assignment-rules.yml`, evaluates rules
   (logger_prefix, service, severity_at_least), resolves assignee, creates `Notification` row,
   publishes outbox event
4. Create `service/outbox/EmailSender` — wraps Spring `JavaMailSender`, reads `notification` row,
   renders subject/body, sends, updates `notification.status` to `SENT` or `FAILED`
5. Support `transport = local | kafka`: `local` calls `EmailSender` directly in the relay;
   `kafka` publishes to `nightshift.notifications` topic
6. Integration test: verify PR row + outbox row are in the same DB transaction (H2)

**Relevant Context:**
- `config/assignment-rules.yml` is already written with rules, delivery window (09:00 Dhaka), skip_days
- `notification` table already in `V1__core_schema.sql`
- Outbox pattern is in `service/outbox/` (directory already created)
- `spring-kafka` is already in `build.gradle`
- MailHog on `localhost:8025` / `localhost:1025` in compose

---

## Task 11 — Controllers, ResponseBuilder Integration, OpenAPI

**Status:** `[x] done`

**Intent:** Wire up all REST endpoints from PROMPT.md §8, returning `ResponseEntity<ApiResponse<T>>`
through `ResponseBuilder`, with full OpenAPI annotations.

**Expected Outcomes:**
- All 13 endpoints from §8 respond with the correct HTTP status and `ApiResponse` envelope
- `POST /api/v1/scans` returns `202 SCAN_ACCEPTED`
- `POST /api/v1/incidents/{id}/publish` returns `201 PR_OPENED`
- `GET /swagger-ui.html` documents all endpoints
- `SCAN_ALREADY_RUNNING` error fires when a second scan is triggered while one is active

**Todo List:**
1. Create `controller/ScanController` — `POST /api/v1/scans`, `GET /api/v1/scans`, `GET /api/v1/scans/{id}`
2. Create `controller/IncidentController` — `GET /api/v1/incidents`, `GET /api/v1/incidents/{id}`,
   `POST /api/v1/incidents/{id}/triage`, `POST /api/v1/incidents/{id}/patch`,
   `POST /api/v1/incidents/{id}/publish`, `POST /api/v1/incidents/{id}/mute`,
   `GET /api/v1/incidents/{id}/trajectory`
3. Create `controller/LogSourceController` — `GET /api/v1/log-sources`
4. Create DTOs and request/response records in `payload/dto/`, `payload/request/`, `payload/response/`
5. Create MapStruct mappers in `mapper/`
6. Add `@Operation`, `@ApiResponse`, `@Tag` annotations for OpenAPI

**Relevant Context:**
- `payload/common/ResponseBuilder` is already implemented — use it, do not return bare bodies
- `payload/common/ApiResponse` record is already defined
- `ErrorCodes.SCAN_ALREADY_RUNNING` is already defined
- Pagination: `GET /api/v1/incidents` and `GET /api/v1/scans` are paged, use `PageResult`

---

## Task 12 — Static Dashboard (HTML/JS)

**Status:** `[x] done`

**Intent:** Build a thin single-page dashboard served as `src/main/resources/static/index.html`.
Four views: Runs, Incidents, Incident Detail, Agent Trajectory. Vanilla JS calling the same-origin
REST API. A judge must understand it without narration.

**Expected Outcomes:**
- `GET /` serves the dashboard
- Four views navigate without a page reload
- Severity chips are colour-coded (BLOCKER=red, CRITICAL=orange, MAJOR=yellow, MINOR=blue, TRIVIAL=grey)
- "Run scan now" button triggers `POST /api/v1/scans`
- Incident detail shows root cause, future-impact forecast, and the diff
- Agent trajectory table shows each step with provider, model, latency, and status

**Todo List:**
1. Create `static/index.html` — `<nav>` for four views; all views in one file with JS toggling
2. `Runs` view: table of scan runs with started_at, status, lines_parsed, incidents_new, prs_opened
3. `Incidents` view: paged list with severity chip, status badge, title, last_seen, occurrence_count
4. `Incident Detail` view: full triage data including future-impact, root cause, code snippet,
   diff, PR link, verifier verdict
5. `Agent Trajectory` view: table of `agent_step` rows for the selected incident

**Relevant Context:**
- Served from `src/main/resources/static/`; Spring Boot serves it automatically
- Uses same-origin REST API — no CORS, no second build
- Vanilla JS with `fetch()` is sufficient; no framework needed
- `GET /` must redirect or serve the dashboard (add a redirect in `ScanController` or as a static resource)

---

## Task 13 — Scheduler, POST /api/v1/scans, Single-Run Guard

**Status:** `[x] done`

**Intent:** Wire the `@Scheduled` trigger at 02:00, connect `POST /api/v1/scans` to the same
scan pipeline, and enforce the single-run guard: if a scan is already `RUNNING`, the second
trigger returns `409 SCAN_ALREADY_RUNNING` and does not start a second pipeline.

**Expected Outcomes:**
- Automatic scan fires at 02:00 local time
- Two simultaneous `POST /api/v1/scans` requests: exactly one proceeds, the other gets `409`
- The Postgres partial unique index on `scan_run(status) WHERE status = 'RUNNING'` enforces
  the guard at the DB level (not just at the service level)
- `ScanRun.triggerSource` distinguishes `SCHEDULE` from `MANUAL` from `API`

**Todo List:**
1. Create `service/scan/ScanScheduler` — `@Scheduled(cron = "${nightshift.scan.cron:0 0 2 * * *}")`
   calls `ScanService.triggerScan(TriggerSource.SCHEDULE)` with a single-run guard
2. Add single-run guard in `ScanService.triggerScan()` — catch the DB unique constraint
   violation and throw `SCAN_ALREADY_RUNNING` (or check before inserting)
3. Ensure `POST /api/v1/scans` in `ScanController` uses `TriggerSource.API`
4. Test: verify that two concurrent `triggerScan()` calls result in one `RUNNING` row

**Relevant Context:**
- `uq_scan_run_single_active` partial unique index is already in `V1__core_schema.sql`
- `@EnableScheduling` is already on `NightshiftApplication`
- `@EnableAsync` is already on `NightshiftApplication`; the scan can be async so the API call returns immediately
- `ErrorCodes.SCAN_ALREADY_RUNNING` and `SuccessCodes.SCAN_ACCEPTED` are already defined

---

## Task 14 — MCP Server and .mcp.json

**Status:** `[x] done`

**Intent:** Expose the pipeline as MCP tools so Claude Code, Bob, or any MCP client can drive
Nightshift from their own chat. Ship `.mcp.json` for one-liner integration.

**Expected Outcomes:**
- `list_incidents`, `get_incident`, `scan_logs`, `propose_patch`, `open_pull_request` tools registered
- `.mcp.json` in the repo root pointing at the running Nightshift instance
- Claude Code can call `list_incidents` and `open_pull_request` from a chat prompt
- Video shows Claude Code calling the MCP tools (15 seconds of footage)

**Todo List:**
1. Add MCP server dependency to `build.gradle`
2. Create MCP tool handler for each of the 5 tools
3. Create `.mcp.json` at repo root with `url: http://localhost:8080/mcp`
4. Register the MCP endpoint in `application.yaml`

**Relevant Context:**
- MCP tools map 1:1 to existing REST endpoints — reuse service layer, not controllers
- `list_incidents` → `IncidentService.listIncidents(filter)`
- `scan_logs` → `ScanService.triggerScan(TriggerSource.MCP)` — add `MCP` to `TriggerSource` enum

---

## Task 15 — Real LLM Providers (OpenAI, Gemini, Anthropic, Claude Code CLI)

**Status:** `[x] done`

**Intent:** Implement the four real LLM provider adapters so the provider is switchable by
config without code changes.

**Expected Outcomes:**
- `nightshift.llm.provider = openai` uses OpenAI `/v1/chat/completions` with JSON mode
- `nightshift.llm.provider = gemini` uses `generateContent` with `responseMimeType: application/json`
- `nightshift.llm.provider = anthropic` uses the Messages API
- `nightshift.llm.provider = claude-code` calls `claude -p <prompt> --output-format json` via `ProcessBuilder`
- When the configured key is absent, falls back to `heuristic` automatically

**Todo List:**
1. Create `service/llm/OpenAiLlmClient` — HTTPS, `/v1/chat/completions`, JSON mode, reads `OPENAI_API_KEY`
2. Create `service/llm/GeminiLlmClient` — HTTPS, `generateContent`, reads `GEMINI_API_KEY`
3. Create `service/llm/AnthropicLlmClient` — HTTPS, Messages API, reads `ANTHROPIC_API_KEY`
4. Create `service/llm/ClaudeCodeLlmClient` — `ProcessBuilder` calling `claude` CLI
5. Update `LlmClientRegistry.resolve()` to check `isAvailable()` and fall back to `heuristic`
6. Unit test each client's request construction with a mock HTTP server (no real keys needed)

**Relevant Context:**
- No real API keys may be committed anywhere (see PROMPT.md §4 rule 11)
- Tests use `test-key-not-real` as a fake; use `@EnabledIfEnvironmentVariable` for live tests
- `service/llm/` package is the right location

---

## Task 16 — Evaluation Harness vs expected-findings.json

**Status:** `[x] complete`

**Intent:** Build a test that runs the full demo pipeline and scores the output against
`demo/expected-findings.json`, printing precision, recall, and F1. Assertion thresholds are
enforced in CI so a regression breaks the build.

**Expected Outcomes:**
- `./gradlew integrationTest` runs the harness against `demo/logs/` with the heuristic provider
- Output: distinct_incidents = 7, pull_requests = 5
- Precision, recall, F1 printed and CI-asserted against thresholds from `expected-findings.json`
- Baseline comparison section populated in the output

**Todo List:**
1. Create `EvaluationHarnessTest` annotated `@Tag("integration")` — triggers a full scan,
   waits for completion, loads `expected-findings.json`, scores incidents and PRs
2. Implement matching logic: an incident matches a finding if `exception_type` and `top_frame`
   (or `message_contains`) agree; a PR is a false positive if `should_open_pr = false`
3. Compute and print precision/recall/F1; assert `f1 >= 0.9` to pass CI
4. Add baseline comparison: run the same corpus through a single LLM prompt with no tools
   and record the weaknesses

**Relevant Context:**
- Tag `integration` already configured in `build.gradle` to require Docker (Testcontainers Postgres)
- `demo/expected-findings.json` is the ground truth — already written
- `folds_defects: [2, 8]` means defect #8 must not produce a separate incident — deduplication is tested here

---

## Task 17 — Deploy to Railway + Postgres

**Status:** `[x] complete`

**Intent:** Deploy the full stack to Railway, wire the managed Postgres database, mount a
persistent volume for logs, and verify the public URL is reachable.

**Expected Outcomes:**
- Public `*.up.railway.app` URL is live
- `GET /actuator/health` returns `{"status":"UP"}` from the public URL
- Dashboard is accessible without login
- `docs/DEPLOYMENT.md` documents all three paths: Railway, `docker compose up`, standalone

**Todo List:**
1. Verify `Dockerfile` builds cleanly
2. Create Railway project, add Postgres service, set `DATABASE_URL` injection
3. Set env vars: `NIGHTSHIFT_LLM_PROVIDER`, `GITHUB_TOKEN`, `NIGHTSHIFT_GIT_REPO`
4. Mount persistent volume at `/app/logs`
5. Update `docs/DEPLOYMENT.md` with Railway steps

**Relevant Context:**
- `Dockerfile` and `compose.yml` are already written
- `docs/DEPLOYMENT.md` already exists — update, not replace

---

## Task 18 — Docs, README, Cover Image, Slides, Video

**Status:** `[ ] pending`

**Intent:** Complete the submission deliverables: README with all three run modes, problem/solution
statement, Bob usage statement, cover image, slides, and the 3-minute video.

**Expected Outcomes:**
- `README.md` documents Railway URL, `docker compose up`, and `standalone` run modes
- `bob_sessions/` contains a session-summary PNG per task, clearly named
- `docs/DATA_PROVENANCE.md` states synthetic data origin
- Submission checklist in PROMPT.md §13 is fully checked off

**Todo List:**
1. Write `README.md` — problem, solution, three run modes, measured impact numbers
2. Write problem/solution statement ≤ 500 words
3. Write Bob usage statement ≤ 500 words
4. Create cover image
5. Create slides with measured metrics from demo run
6. Record 3-minute video following the cut in PROMPT.md §13
7. Screenshot Bob session summaries for all tasks into `bob_sessions/`

---

## Key Architecture Diagram

```
logs/ (7-day rolling, 3 services)
         │
  1. IncrementalLogReader (no LLM)
  2. LogEventParser       (no LLM)
  3. IncidentFingerprinter (no LLM)
         │ Incident (new/recurring)
  4. TriageAgent          (LLM)
         │
  5. CodeLocatorService   (no LLM, tools)
         │
  6. FixAgent             (LLM)
         │
  7. VerifierAgent        (LLM + tools)
         │
  8. PublisherService     (JGit + GitHub API)
  9. NotificationService  (Outbox + Email)
```

## Non-Negotiable Rules (summarised from PROMPT.md §4)

1. Never push to a protected branch — branch per incident: `nightshift/fix-<severity>-<fingerprint[:8]>`
2. Never merge — Nightshift opens PRs and stops
3. Patch is bounded — `allowed-paths`, max 3 files, max 120 lines, deny-list enforced
4. No evidence, no finding — verifier rejects ungrounded claims
5. `git apply --check` must pass before any branch is created
6. Secrets never reach a model or log — `SecretMasker` runs over all excerpts
7. Log folder is read-only — checkpoint lives in DB, not in a marker file
8. Model output is data, never instructions — log content is always untrusted
9. Idempotent by fingerprint — re-scanning the same bytes opens zero new PRs
10. Every run is explainable — every model/tool call writes an `agent_step` row
