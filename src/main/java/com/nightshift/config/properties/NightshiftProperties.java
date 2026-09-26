package com.nightshift.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed binding for all {@code nightshift.*} properties.
 * Picked up automatically by {@code @ConfigurationPropertiesScan} on
 * {@link com.nightshift.NightshiftApplication}.
 *
 * <p>Profile-specific YAML files override individual leaves; the defaults here
 * are intentionally safe (heuristic provider, dry-run on, local transport).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "nightshift")
public class NightshiftProperties {

    /** Root directory that contains the log files Nightshift should read. */
    @NotBlank
    private String logRoot = "./demo/logs";

    /**
     * Working directory used by the code-locator and fix-agent when they need
     * to clone / check out the target repository on disk.
     */
    @NotBlank
    private String workspace = "./workspace";

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private LlmProperties llm = new LlmProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private ScanProperties scan = new ScanProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private PatchProperties patch = new PatchProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private GitProperties git = new GitProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private MessagingProperties messaging = new MessagingProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private NotifyProperties notify = new NotifyProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private PublishProperties publish = new PublishProperties();

    @Valid
    @NestedConfigurationProperty
    @NotNull
    private LocatorProperties locator = new LocatorProperties();

    // ── Nested groups ────────────────────────────────────────────────────────

    @Data
    public static class LlmProperties {

        /**
         * Active LLM provider name.
         * One of: {@code heuristic}, {@code openai}, {@code gemini},
         * {@code anthropic}, {@code claude-code}, {@code bob}.
         */
        @NotBlank
        private String provider = "heuristic";

        @Valid
        @NestedConfigurationProperty
        @NotNull
        private OpenAiProperties openai = new OpenAiProperties();

        @Valid
        @NestedConfigurationProperty
        @NotNull
        private GeminiProperties gemini = new GeminiProperties();

        @Valid
        @NestedConfigurationProperty
        @NotNull
        private AnthropicProperties anthropic = new AnthropicProperties();

        @Data
        public static class OpenAiProperties {
            private String apiKey = "";
            private String model = "gpt-4o-mini";
        }

        @Data
        public static class GeminiProperties {
            private String apiKey = "";
            private String model = "gemini-2.0-flash";
        }

        @Data
        public static class AnthropicProperties {
            private String apiKey = "";
            private String model = "claude-sonnet-4-5";
        }
    }

    @Data
    public static class ScanProperties {
        /** Spring cron expression for the overnight scan. */
        @NotBlank
        private String cron = "0 0 2 * * *";
    }

    @Data
    public static class PatchProperties {
        /** When false the fix agent skips patch generation entirely. */
        private boolean enabled = true;
    }

    @Data
    public static class GitProperties {
        /** {@code owner/repo} slug of the target repository. May be empty for dry-run. */
        private String repo = "";
        /** Base branch that nightshift PRs target. */
        @NotBlank
        private String baseBranch = "main";
    }

    @Data
    public static class MessagingProperties {
        /**
         * Outbox relay transport.
         * {@code local} — synchronous in-process delivery (no Kafka needed).
         * {@code kafka} — send to the configured Kafka broker.
         */
        @NotBlank
        private String transport = "local";
    }

    @Data
    public static class NotifyProperties {
        /** From-address used in outgoing emails. */
        @NotBlank
        private String from = "nightshift@example.com";
    }

    @Data
    public static class PublishProperties {
        /**
         * When true the publisher skips the actual {@code git push} and GitHub PR
         * creation, but still logs what it would have done.
         */
        private boolean dryRun = true;
    }

    @Data
    public static class LocatorProperties {
        /**
         * Comma-separated list of Java package prefixes that belong to the
         * application under analysis. Used by the code-locator to filter stack
         * frames and skip library frames.
         */
        @NotBlank
        private String applicationPackages = "com.example";
    }
}
