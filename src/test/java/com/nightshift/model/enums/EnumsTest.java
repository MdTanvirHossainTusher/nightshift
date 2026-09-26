package com.nightshift.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every enum expected by the schema and plan is present and
 * contains the required constants. These tests run without Spring context.
 */
class EnumsTest {

    @Test
    void triggerSource_containsAllValues() {
        assertThat(TriggerSource.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("SCHEDULE", "MANUAL", "API", "MCP");
    }

    @Test
    void scanStatus_containsAllValues() {
        assertThat(ScanStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("RUNNING", "COMPLETED", "FAILED");
    }

    @Test
    void incidentStatus_containsAllValues() {
        assertThat(IncidentStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "NEW", "TRIAGED", "TRIAGE_FAILED", "FIX_PROPOSED", "FIX_VERIFIED",
                        "PR_OPEN", "PR_MERGED", "RESOLVED", "MUTED");
    }

    @Test
    void severity_containsAllValues() {
        assertThat(Severity.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL");
    }

    @Test
    void confidenceLevel_containsAllValues() {
        assertThat(ConfidenceLevel.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("HIGH", "MEDIUM", "LOW");
    }

    @Test
    void patchStatus_containsAllValues() {
        assertThat(PatchStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("DRAFT", "VERIFIED", "REJECTED", "PUBLISHED");
    }

    @Test
    void verifierVerdict_containsAllValues() {
        assertThat(VerifierVerdict.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("PASS", "FAIL", "SKIPPED");
    }

    @Test
    void prState_containsAllValues() {
        assertThat(PrState.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("OPEN", "MERGED", "CLOSED", "FAILED");
    }

    @Test
    void notificationChannel_containsAllValues() {
        assertThat(NotificationChannel.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("EMAIL", "WEBHOOK");
    }

    @Test
    void notificationStatus_containsAllValues() {
        assertThat(NotificationStatus.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("PENDING", "SENT", "FAILED");
    }

    @Test
    void agentRole_containsAllValues() {
        assertThat(AgentRole.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("TRIAGE", "LOCATE", "FIX", "VERIFY", "PUBLISH");
    }

    @Test
    void stepType_containsAllValues() {
        assertThat(StepType.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("MODEL", "TOOL");
    }
}
