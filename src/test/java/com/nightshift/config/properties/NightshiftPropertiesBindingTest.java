package com.nightshift.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link NightshiftProperties} is correctly bound from YAML
 * by the Spring {@code @ConfigurationProperties} machinery.
 *
 * <p>Uses a minimal Spring context (no web, no JPA, no Flyway) loaded from
 * the test property source so the test is fast and hermetic.
 */
@SpringBootTest(
        classes = NightshiftPropertiesBindingTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        // top-level
        "nightshift.log-root=/var/log/test",
        "nightshift.workspace=/tmp/ws",
        // llm
        "nightshift.llm.provider=openai",
        "nightshift.llm.openai.api-key=sk-test-key",
        "nightshift.llm.openai.model=gpt-4o",
        "nightshift.llm.gemini.api-key=gemini-key",
        "nightshift.llm.gemini.model=gemini-1.5-pro",
        "nightshift.llm.anthropic.api-key=anthro-key",
        "nightshift.llm.anthropic.model=claude-3-opus-20240229",
        // scan
        "nightshift.scan.cron=0 30 3 * * *",
        // patch
        "nightshift.patch.enabled=false",
        // git
        "nightshift.git.repo=org/target-repo",
        "nightshift.git.base-branch=develop",
        // messaging
        "nightshift.messaging.transport=kafka",
        // notify
        "nightshift.notify.from=bot@company.com",
        // publish
        "nightshift.publish.dry-run=false",
        // locator
        "nightshift.locator.application-packages=com.company.app",
})
class NightshiftPropertiesBindingTest {

    @EnableConfigurationProperties(NightshiftProperties.class)
    static class Config {
        // minimal context: only the properties class, nothing else
    }

    @Autowired
    private NightshiftProperties props;

    // ── Top-level ─────────────────────────────────────────────────────────────

    @Test
    void binding_logRoot() {
        assertThat(props.getLogRoot()).isEqualTo("/var/log/test");
    }

    @Test
    void binding_workspace() {
        assertThat(props.getWorkspace()).isEqualTo("/tmp/ws");
    }

    // ── LLM ──────────────────────────────────────────────────────────────────

    @Test
    void binding_llm_provider() {
        assertThat(props.getLlm().getProvider()).isEqualTo("openai");
    }

    @Test
    void binding_llm_openai() {
        assertThat(props.getLlm().getOpenai().getApiKey()).isEqualTo("sk-test-key");
        assertThat(props.getLlm().getOpenai().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void binding_llm_gemini() {
        assertThat(props.getLlm().getGemini().getApiKey()).isEqualTo("gemini-key");
        assertThat(props.getLlm().getGemini().getModel()).isEqualTo("gemini-1.5-pro");
    }

    @Test
    void binding_llm_anthropic() {
        assertThat(props.getLlm().getAnthropic().getApiKey()).isEqualTo("anthro-key");
        assertThat(props.getLlm().getAnthropic().getModel()).isEqualTo("claude-3-opus-20240229");
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    @Test
    void binding_scan_cron() {
        assertThat(props.getScan().getCron()).isEqualTo("0 30 3 * * *");
    }

    // ── Patch ─────────────────────────────────────────────────────────────────

    @Test
    void binding_patch_disabled() {
        assertThat(props.getPatch().isEnabled()).isFalse();
    }

    // ── Git ───────────────────────────────────────────────────────────────────

    @Test
    void binding_git_repo() {
        assertThat(props.getGit().getRepo()).isEqualTo("org/target-repo");
    }

    @Test
    void binding_git_baseBranch() {
        assertThat(props.getGit().getBaseBranch()).isEqualTo("develop");
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    @Test
    void binding_messaging_transport() {
        assertThat(props.getMessaging().getTransport()).isEqualTo("kafka");
    }

    // ── Notify ────────────────────────────────────────────────────────────────

    @Test
    void binding_notify_from() {
        assertThat(props.getNotify().getFrom()).isEqualTo("bot@company.com");
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    @Test
    void binding_publish_dryRunFalse() {
        assertThat(props.getPublish().isDryRun()).isFalse();
    }

    // ── Locator ───────────────────────────────────────────────────────────────

    @Test
    void binding_locator_applicationPackages() {
        assertThat(props.getLocator().getApplicationPackages()).isEqualTo("com.company.app");
    }
}
