package com.nightshift.service.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeLlmClientTest {

    @Test
    void testProviderName() {
        ClaudeCodeLlmClient client = new ClaudeCodeLlmClient();
        assertThat(client.provider()).isEqualTo("claude-code");
    }

    @Test
    void testIsAvailableDoesNotThrow() {
        ClaudeCodeLlmClient client = new ClaudeCodeLlmClient();
        // Should evaluate cleanly to boolean without throwing exception
        boolean available = client.isAvailable();
        assertThat(available).isIn(true, false);
    }
}