package com.nightshift.config.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link NightshiftProperties} — no Spring context loaded.
 * Verifies that every nested group is initialised with the correct defaults
 * and that the object graph is fully non-null after construction.
 */
class NightshiftPropertiesTest {

    private final NightshiftProperties props = new NightshiftProperties();

    // ── Top-level defaults ────────────────────────────────────────────────────

    @Test
    void defaults_logRoot() {
        assertThat(props.getLogRoot()).isEqualTo("./demo/logs");
    }

    @Test
    void defaults_workspace() {
        assertThat(props.getWorkspace()).isEqualTo("./workspace");
    }

    // ── LLM defaults ─────────────────────────────────────────────────────────

    @Test
    void defaults_llm_providerIsHeuristic() {
        assertThat(props.getLlm().getProvider()).isEqualTo("heuristic");
    }

    @Test
    void defaults_llm_openaiModelDefault() {
        assertThat(props.getLlm().getOpenai().getModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void defaults_llm_geminiModelDefault() {
        assertThat(props.getLlm().getGemini().getModel()).isEqualTo("gemini-2.0-flash");
    }

    @Test
    void defaults_llm_anthropicModelDefault() {
        assertThat(props.getLlm().getAnthropic().getModel()).isEqualTo("claude-3-7-sonnet-20250219");
    }

    @Test
    void defaults_llm_apiKeysAreBlankByDefault() {
        assertThat(props.getLlm().getOpenai().getApiKey()).isEmpty();
        assertThat(props.getLlm().getGemini().getApiKey()).isEmpty();
        assertThat(props.getLlm().getAnthropic().getApiKey()).isEmpty();
    }

    // ── Scan defaults ─────────────────────────────────────────────────────────

    @Test
    void defaults_scan_cronIs2AmDaily() {
        assertThat(props.getScan().getCron()).isEqualTo("0 0 2 * * *");
    }

    // ── Patch defaults ────────────────────────────────────────────────────────

    @Test
    void defaults_patch_enabledByDefault() {
        assertThat(props.getPatch().isEnabled()).isTrue();
    }

    // ── Git defaults ──────────────────────────────────────────────────────────

    @Test
    void defaults_git_baseBranchIsMain() {
        assertThat(props.getGit().getBaseBranch()).isEqualTo("main");
    }

    @Test
    void defaults_git_repoIsBlankByDefault() {
        assertThat(props.getGit().getRepo()).isEmpty();
    }

    // ── Messaging defaults ────────────────────────────────────────────────────

    @Test
    void defaults_messaging_transportIsLocal() {
        assertThat(props.getMessaging().getTransport()).isEqualTo("local");
    }

    // ── Notify defaults ───────────────────────────────────────────────────────

    @Test
    void defaults_notify_fromAddress() {
        assertThat(props.getNotify().getFrom()).isEqualTo("nightshift@example.com");
    }

    // ── Publish defaults ──────────────────────────────────────────────────────

    @Test
    void defaults_publish_dryRunIsTrue() {
        assertThat(props.getPublish().isDryRun()).isTrue();
    }

    // ── Locator defaults ──────────────────────────────────────────────────────

    @Test
    void defaults_locator_applicationPackages() {
        assertThat(props.getLocator().getApplicationPackages()).isEqualTo("com.example");
    }

    // ── Nested objects are never null ─────────────────────────────────────────

    @Test
    void nestedGroups_neverNull() {
        assertThat(props.getLlm()).isNotNull();
        assertThat(props.getLlm().getOpenai()).isNotNull();
        assertThat(props.getLlm().getGemini()).isNotNull();
        assertThat(props.getLlm().getAnthropic()).isNotNull();
        assertThat(props.getScan()).isNotNull();
        assertThat(props.getPatch()).isNotNull();
        assertThat(props.getGit()).isNotNull();
        assertThat(props.getMessaging()).isNotNull();
        assertThat(props.getNotify()).isNotNull();
        assertThat(props.getPublish()).isNotNull();
        assertThat(props.getLocator()).isNotNull();
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    @Test
    void mutation_llmProvider_canBeOverridden() {
        props.getLlm().setProvider("openai");
        assertThat(props.getLlm().getProvider()).isEqualTo("openai");
    }

    @Test
    void mutation_messaging_transportCanBeKafka() {
        props.getMessaging().setTransport("kafka");
        assertThat(props.getMessaging().getTransport()).isEqualTo("kafka");
    }

    @Test
    void mutation_publish_dryRunCanBeDisabled() {
        props.getPublish().setDryRun(false);
        assertThat(props.getPublish().isDryRun()).isFalse();
    }
}
