package com.nightshift.service.llm;

/**
 * Input payload for a single LLM call.
 *
 * @param systemPromptPath classpath resource path (under {@code prompts/}) that
 *                         contains the system instruction text; may be {@code null}
 *                         when no system prompt is needed.
 * @param userPrompt       the user-turn content (filled-in template, evidence, etc.)
 * @param jsonSchemaHint   optional JSON schema string the provider should conform to;
 *                         {@code null} if no structured output is required.
 */
public record LlmRequest(
        String systemPromptPath,
        String userPrompt,
        String jsonSchemaHint
) {

    /** Convenience factory with no system prompt and no schema constraint. */
    public static LlmRequest of(String userPrompt) {
        return new LlmRequest(null, userPrompt, null);
    }

    /** Convenience factory with a system-prompt path but no schema constraint. */
    public static LlmRequest of(String systemPromptPath, String userPrompt) {
        return new LlmRequest(systemPromptPath, userPrompt, null);
    }
}
