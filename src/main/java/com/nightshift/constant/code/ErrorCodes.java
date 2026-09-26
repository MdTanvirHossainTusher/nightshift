package com.nightshift.constant.code;

/**
 * Stable, machine-readable error codes. Clients branch on these, never on the
 * human message, so a code is part of the API contract: add freely, rename never.
 */
public interface ErrorCodes {

    // ── Generic ───────────────────────────────────────────────────────────────
    String VALIDATION_ERROR   = "VALIDATION_ERROR";
    String NOT_FOUND          = "NOT_FOUND";
    String BAD_REQUEST        = "BAD_REQUEST";
    String CONFLICT           = "CONFLICT";
    String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    String RATE_LIMITED       = "RATE_LIMITED";
    String INTERNAL_ERROR     = "INTERNAL_SERVER_ERROR";

    // ── Database integrity ────────────────────────────────────────────────────
    String DUPLICATE_ENTRY                  = "DUPLICATE_ENTRY";
    String REFERENCED_RECORD_MISSING        = "REFERENCED_RECORD_MISSING";
    String DATA_INTEGRITY_VIOLATION         = "DATA_INTEGRITY_VIOLATION";
    String DATABASE_TEMPORARILY_UNAVAILABLE = "DATABASE_TEMPORARILY_UNAVAILABLE";

    // ── Scanning / ingestion ──────────────────────────────────────────────────
    String LOG_SOURCE_UNREADABLE = "LOG_SOURCE_UNREADABLE";
    String SCAN_ALREADY_RUNNING  = "SCAN_ALREADY_RUNNING";
    String NOTHING_TO_SCAN       = "NOTHING_TO_SCAN";

    // ── Agents / model providers ──────────────────────────────────────────────
    String LLM_PROVIDER_UNAVAILABLE = "LLM_PROVIDER_UNAVAILABLE";
    String LLM_RESPONSE_UNPARSEABLE = "LLM_RESPONSE_UNPARSEABLE";
    String AGENT_BUDGET_EXCEEDED    = "AGENT_BUDGET_EXCEEDED";
    String EVIDENCE_REJECTED        = "EVIDENCE_REJECTED";

    // ── Patch / publishing ────────────────────────────────────────────────────
    String PATCH_DOES_NOT_APPLY        = "PATCH_DOES_NOT_APPLY";
    String PATCH_TOUCHES_DENIED_PATH   = "PATCH_TOUCHES_DENIED_PATH";
    String PATCH_TOO_LARGE             = "PATCH_TOO_LARGE";
    String PATCH_UNBALANCED            = "PATCH_UNBALANCED";
    String SOURCE_FILE_NOT_LOCATED     = "SOURCE_FILE_NOT_LOCATED";
    String PR_ALREADY_OPEN             = "PR_ALREADY_OPEN";
    String GIT_REMOTE_REJECTED         = "GIT_REMOTE_REJECTED";
    String UPSTREAM_SERVICE_UNAVAILABLE = "UPSTREAM_SERVICE_UNAVAILABLE";

    // ── Notification ──────────────────────────────────────────────────────────
    String NO_ASSIGNEE_MATCHED = "NO_ASSIGNEE_MATCHED";
    String NOTIFICATION_FAILED = "NOTIFICATION_FAILED";
}
