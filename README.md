# Nightshift

**Your logs get triaged while you sleep. You wake up to pull requests.**

Nightshift is a scheduled agentic pipeline that reads the application logs nobody has read yet,
groups them into distinct incidents, diagnoses each one against the real source code, writes a
patch, opens one pull request per problem, and emails the owning developer — so the working day
starts with a review queue instead of a log file.

Built for the **IBM Bob 2.0 Hackathon** (developer-workflow track: debugging and application
maintenance).

---

## The problem

A log-visible defect costs **2–4 hours of senior developer attention** today: notice it, find
the file, grep past the noise, work out which of 23 000 lines are the same fault, map a stack
frame to a line of code, decide whether it matters, then write the fix and find a reviewer. Most
defects never get that attention at all — a `WARN` saying a connection pool is leaking is free
to ignore for six weeks and then takes the service down at month-end.

Log aggregators stop at the dashboard. They alert. They do not fix.

## What it does

```
logs/ ──▶ incremental read ──▶ parse ──▶ fingerprint ──▶ TRIAGE ──▶ locate code
                                                                        │
      email ◀── outbox ◀── pull request ◀── VERIFY ◀── propose patch ◀───┘
```

The model is used in exactly three places — triage, patch, verify. Reading bytes, parsing lines,
fingerprinting, resolving stack frames, branching and pushing are deterministic code, because
they are cheap, exact and testable. That split is the engineering argument of the project.

Each pull request carries:

- **Severity**, weighted by frequency × trend × blast radius — not by log level
- **Why it happens** — the root cause, grounded in the source the agent actually read
- **What it costs if you do not fix it now** — the forecast
- **Evidence** — redacted log excerpts with timestamps
- **The patch**, plus how to verify it

It never merges. It never pushes to `main`. A human accepts or rejects.

## Status

All 18 tasks are fully implemented, verified, and passing continuous integration.

| Resource | Location | Description |
| --- | --- | --- |
| **Bob Evidence** | [`bob_sessions/`](bob_sessions/) | Task session summaries and execution artifacts from Bob IDE |
| **Demo Dataset** | [`demo/`](demo/) | 7 days × 3 services, 20,127+ lines, 8 seeded defects |
| **Ground Truth** | [`demo/expected-findings.json`](demo/expected-findings.json) | Evaluated benchmark scored at 100% precision and recall |
| **Database Schema** | [`V1__core_schema.sql`](src/main/resources/db/migration/V1__core_schema.sql) | 9 tables with UUIDv7 PKs, transactional outbox, and trajectory audit |
| **Assignment Rules** | [`config/assignment-rules.yml`](config/assignment-rules.yml) | Service and logger prefix routing for PR assignments |
| **Deployment Guide** | [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Railway, Docker Compose, and Standalone run instructions |

---

## Measured Impact (from Demo Run)

The pipeline was evaluated against ground truth (`demo/expected-findings.json`) using the evaluation harness (`EvaluationHarnessTest`):

| Metric | Measured Result | Significance |
| --- | --- | --- |
| **Log lines ingested** | **20,127 lines** across 21 files | Full 7-day rolling window across 3 services |
| **Distinct incidents found** | **7 incidents** (from 8 seeded defects) | Folded #2 and #8 into a single root cause |
| **Noise reduction ratio** | **2,875 : 1** | Over 20,000 lines compressed to 7 actionable incident cards |
| **Pull requests opened** | **5 pull requests** | #6 suppressed (already handled with bounded retry); #7 suppressed (framework noise) |
| **Precision** | **1.000 (100.0%)** | Zero spurious PRs or false positive alerts |
| **Recall** | **1.000 (100.0%)** | All ground truth defects detected |
| **F1 Score** | **1.000 (100.0%)** | Exceeds the CI threshold requirement (≥ 0.90) |

---

## IBM Bob IDE Usage Statement

IBM Bob IDE was the central development environment used to build Nightshift from concept to final verified build across all 18 development tasks:

1. **Document Understanding & Architecture Derivation:** Bob was provided `PROMPT.md` and the database migration `V1__core_schema.sql` to derive JPA entities, repositories, and domain models cleanly matching the schema constraints.
2. **Parallel Task Execution:** Independent subsystems were implemented concurrently using Bob's parallel task capability—specifically Triage Agent with Code Locator (Tasks 5 & 6) and Publisher with Outbox Notifier (Tasks 9 & 10).
3. **Specialized Agent Roles:** Subagents were structured to match Nightshift's own pipeline architecture: dedicated roles for Log Analysis, Code Location, Fix Formulation, and Adversarial Verification.
4. **Agent Mode Iterate-Until-Green Loops:** Bob was run in iterative agent mode to build and verify complex multi-step subsystems:
   - Incremental log reader and SHA-256 fingerprinting deduplication (Task 3).
   - Adversarial verification checking patch applicability and evidence grounding (Task 8).
   - Evaluation harness scoring precision, recall, and F1 against `expected-findings.json` (Task 16).

---

## Running it

Three paths, in increasing order of setup. **All three work with no API key** — with none
configured, Nightshift falls back to an offline `heuristic` provider and the whole pipeline
still runs.

```bash
# 1. No Docker, no database — H2 in memory
./gradlew bootRun --args='--spring.profiles.active=standalone'

# 2. The full local stack: Postgres + MailHog + the app
docker compose up --build
#    app       http://localhost:8080 (Web Dashboard)
#    email     http://localhost:8025 (MailHog notification inbox)
#    api docs  http://localhost:8080/swagger-ui.html
#    mcp       http://localhost:8080/mcp

# 3. Cloud / Production deployment on Railway
#    See docs/DEPLOYMENT.md for Railway managed Postgres configuration
```

Copy `.env.example` to `.env` to add a real model provider (`openai`, `gemini`, `anthropic`, `claude-code`), a GitHub token, or SMTP.
`NIGHTSHIFT_PUBLISH_DRY_RUN=true` is the default: everything runs except the external push and PR.

## Web Dashboard

Nightshift includes a zero-dependency static web dashboard served directly from `http://localhost:8080/`:
- **Runs View:** Status, lines parsed, bytes read, incidents found, PRs opened, and run duration. Includes a **Run scan now** button.
- **Incidents View:** Searchable, filterable by severity chip (`BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `TRIVIAL`), status, and service.
- **Incident Detail View:** Root cause explanation, future-impact forecast, redacted log evidence, and side-by-side unified diff with test plan.
- **Agent Trajectory View:** Complete audit trail of all model and tool calls per incident with token counts and latency.

## Multi-Agent Interoperability & MCP

- **Nightshift uses any model:** `openai`, `gemini`, `anthropic`, `claude-code` CLI, or offline `heuristic` client switchable via `nightshift.llm.provider`.
- **Other agents drive Nightshift:** Native Model Context Protocol (MCP) server running at `/mcp` (both JSON-RPC 2.0 and Server-Sent Events). Registered tools:
  - `list_incidents`: List recent incidents with severity, status, and occurrence counts.
  - `get_incident`: Fetch detailed diagnosis, root cause, and forecast.
  - `scan_logs`: Trigger an immediate log scan.
  - `propose_patch`: Generate candidate patch diff for an incident.
  - `open_pull_request`: Publish verified patch to a dedicated git branch.

## Stack

Java 21 · Spring Boot 3.4.3 · Gradle · PostgreSQL 16 + Flyway · JGit · transactional outbox ·
Docker · MCP Server · release-please · GitHub Actions

## Data

**Every byte of data in this repository is synthetic and generated by code in this repository.**
Nothing was collected, scraped, exported from a production system, or derived from a real person
or organisation. No client data, no company confidential data, no personal information, nothing
from social media.

`demo/generate-logs.mjs` composes the log lines from hand-written templates with values from a
fixed-seed PRNG, so regeneration is byte-identical. Identifiers are non-identifying by
construction: `example.com` addresses (RFC 2606), `192.0.2.x` addresses (RFC 5737 TEST-NET-1),
`com.example.*` packages, placeholder names, random integers for ids and amounts. There are no
credentials in the dataset, real or plausible.

`demo/target-repo/` is a small fictional Java application written for this project, holding the
eight seeded defects. It exists so the stack traces point at source that actually exists.

Nightshift is written from scratch for this hackathon. No employer-confidential source, schema,
configuration or log output is included. Third-party dependencies are the Apache/EPL/MIT
libraries declared in `build.gradle`; no dataset, corpus or model weights are redistributed.

**Pointing it at real logs?** Extend `config/redaction.yml` for your own identifier formats,
prefer `NIGHTSHIFT_LLM_PROVIDER=heuristic` (or a model inside your own boundary) if the logs may
contain regulated data, keep `NIGHTSHIFT_PUBLISH_DRY_RUN=true` until you have read patches you
agree with, and mount the folder read-only — the scan checkpoint lives in the database, so
Nightshift never needs to write there.

## Licence

MIT.
