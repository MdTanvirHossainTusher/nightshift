package com.nightshift.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link HeuristicLlmClient}.
 *
 * <p>Asserts that the heuristic triage produces all 7 distinct incidents at the
 * severities required by {@code demo/expected-findings.json}.
 */
class HeuristicLlmClientTest {

    private final HeuristicLlmClient client = new HeuristicLlmClient();

    // ── provider / isAvailable ───────────────────────────────────────────────

    @Test
    void provider_returnsHeuristic() {
        assertThat(client.provider()).isEqualTo("heuristic");
    }

    @Test
    void isAvailable_alwaysTrue() {
        assertThat(client.isAvailable()).isTrue();
    }

    // ── Triage — all 7 demo incidents ────────────────────────────────────────

    static Stream<Arguments> triageCases() {
        return Stream.of(
            // promptFragment, expectedSeverity, expectedCategory, expectPr
            Arguments.of(
                "java.sql.SQLTransientConnectionException: could not acquire a connection",
                "CRITICAL", "RESOURCE_LEAK", true, (String) null),
            Arguments.of(
                "java.lang.NullPointerException: Cannot invoke middleName.charAt",
                "MAJOR", "NULL_DEREFERENCE", true, (String) null),
            Arguments.of(
                "com.example.sync.InventoryReportService - Slow report build took 4200ms",
                "MAJOR", "PERFORMANCE", true, (String) null),
            Arguments.of(
                "com.example.payment.PaymentRetryClient - Bank API rate limited, retry attempt 312",
                "CRITICAL", "RETRY_STORM", true, (String) null),
            Arguments.of(
                "com.example.sync.ShipmentEventConsumer$DeserializationException: bad payload",
                "MAJOR", "DATA_LOSS", true, (String) null),
            Arguments.of(
                "com.example.farmer.FarmerProfileService$OptimisticLockException: version mismatch",
                "MINOR", "CONCURRENCY", false, "already_handled"),
            Arguments.of(
                "org.springframework.boot.context.config.ConfigDataEnvironment - deprecated property",
                "TRIVIAL", "MAINTENANCE", false, "framework_noise")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}/{2}")
    @MethodSource("triageCases")
    void triage_matchesExpectedFindings(String promptFragment,
                                        String expectedSeverity,
                                        String expectedCategory,
                                        boolean expectPr,
                                        String expectedVerdict) {
        LlmResponse response = client.complete(LlmRequest.of(promptFragment));
        String json = response.responseText();

        assertThat(json)
                .as("severity for '%s'", promptFragment)
                .contains("\"severity\": \"" + expectedSeverity + "\"");

        assertThat(json)
                .as("category for '%s'", promptFragment)
                .contains("\"category\": \"" + expectedCategory + "\"");

        String prValue = expectPr ? "true" : "false";
        assertThat(json)
                .as("should_open_pr for '%s'", promptFragment)
                .contains("\"should_open_pr\": " + prValue);

        if (expectedVerdict != null) {
            assertThat(json)
                    .as("verdict for '%s'", promptFragment)
                    .contains("\"verdict\": \"" + expectedVerdict + "\"");
        }
    }

    // ── Triage — all 7 incidents produce distinct outputs ────────────────────

    @Test
    void triage_allSevenIncidentsProduceDistinctResponses() {
        String[] prompts = {
            "java.sql.SQLTransientConnectionException: pool exhausted",
            "java.lang.NullPointerException: middleName is null",
            "Slow report build for region NORTH",
            "PaymentRetryClient rate limited attempt 5",
            "DeserializationException: unexpected token",
            "OptimisticLockException: version check failed",
            "ConfigDataEnvironment deprecated spring.profiles"
        };

        var responses = new java.util.LinkedHashSet<String>();
        for (String prompt : prompts) {
            responses.add(client.complete(LlmRequest.of(prompt)).responseText());
        }

        assertThat(responses)
                .as("Each of the 7 demo incidents must produce a distinct triage response")
                .hasSize(7);
    }

    // ── Token counts ─────────────────────────────────────────────────────────

    @Test
    void complete_tokensAreZeroForOfflineProvider() {
        LlmResponse r = client.complete(LlmRequest.of("java.lang.NullPointerException: foo"));
        assertThat(r.tokensIn()).isZero();
        assertThat(r.tokensOut()).isZero();
    }

    // ── Fix diffs — all 5 PR-worthy incidents return a non-empty diff ────────

    static Stream<Arguments> fixCases() {
        return Stream.of(
            Arguments.of("fix_request SQLTransientConnectionException"),
            Arguments.of("fix_request NullPointerException middleName"),
            Arguments.of("fix_request Slow report build"),
            Arguments.of("fix_request rate limited PaymentRetryClient"),
            Arguments.of("fix_request DeserializationException")
        );
    }

    @ParameterizedTest(name = "[{index}] fix: {0}")
    @MethodSource("fixCases")
    void fix_returnsUnifiedDiffForPrWorthyIncidents(String prompt) {
        LlmResponse response = client.complete(LlmRequest.of(prompt));
        assertThat(response.responseText())
                .as("expected a non-empty unified diff for: %s", prompt)
                .isNotBlank()
                .contains("---")
                .contains("+++")
                .contains("@@");
    }

    // ── Registry integration ─────────────────────────────────────────────────

    @Test
    void registry_resolvesHeuristicByName() {
        com.nightshift.config.properties.NightshiftProperties props =
                new com.nightshift.config.properties.NightshiftProperties();
        props.getLlm().setProvider("heuristic");

        LlmClientRegistry registry = new LlmClientRegistry(
                java.util.List.of(client), props);

        assertThat(registry.resolve("heuristic")).isSameAs(client);
        assertThat(registry.resolve()).isSameAs(client);
    }

    @Test
    void registry_throwsForUnknownProvider() {
        com.nightshift.config.properties.NightshiftProperties props =
                new com.nightshift.config.properties.NightshiftProperties();
        LlmClientRegistry registry = new LlmClientRegistry(
                java.util.List.of(client), props);

        assertThatThrownBy(() -> registry.resolve("openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void registry_fallsBackToHeuristicWhenConfiguredProviderUnavailable() {
        // Simulate an openai client that is not available (no API key)
        LlmClient unavailableOpenAi = new LlmClient() {
            @Override public String provider() { return "openai"; }
            @Override public LlmResponse complete(LlmRequest r) { throw new UnsupportedOperationException(); }
            @Override public boolean isAvailable() { return false; }
        };

        com.nightshift.config.properties.NightshiftProperties props =
                new com.nightshift.config.properties.NightshiftProperties();
        props.getLlm().setProvider("openai");

        LlmClientRegistry registry = new LlmClientRegistry(
                java.util.List.of(unavailableOpenAi, client), props);

        // Should fall back to heuristic because openai.isAvailable() == false
        assertThat(registry.resolve()).isSameAs(client);
    }
}
