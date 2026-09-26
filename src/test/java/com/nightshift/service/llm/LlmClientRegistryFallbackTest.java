package com.nightshift.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientRegistryFallbackTest {

    @Test
    void testFallsBackToHeuristicWhenOpenAiKeyMissing() {
        NightshiftProperties properties = new NightshiftProperties();
        properties.getLlm().setProvider("openai");
        properties.getLlm().getOpenai().setApiKey(""); // Missing key

        HeuristicLlmClient heuristic = new HeuristicLlmClient();
        OpenAiLlmClient openai = new OpenAiLlmClient(properties, new ObjectMapper());

        LlmClientRegistry registry = new LlmClientRegistry(List.of(heuristic, openai), properties);

        LlmClient active = registry.resolve();
        assertThat(active.provider()).isEqualTo("heuristic");
    }

    @Test
    void testSelectsOpenAiWhenKeyPresent() {
        NightshiftProperties properties = new NightshiftProperties();
        properties.getLlm().setProvider("openai");
        properties.getLlm().getOpenai().setApiKey("test-valid-key");

        HeuristicLlmClient heuristic = new HeuristicLlmClient();
        OpenAiLlmClient openai = new OpenAiLlmClient(properties, new ObjectMapper());

        LlmClientRegistry registry = new LlmClientRegistry(List.of(heuristic, openai), properties);

        LlmClient active = registry.resolve();
        assertThat(active.provider()).isEqualTo("openai");
    }
}