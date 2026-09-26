package com.nightshift.service.llm;

/**
 * Output from a single LLM call.
 *
 * @param responseText raw text returned by the model (or generated heuristically)
 * @param tokensIn     number of input tokens consumed; 0 for offline providers
 * @param tokensOut    number of output tokens produced; 0 for offline providers
 */
public record LlmResponse(
        String responseText,
        int tokensIn,
        int tokensOut
) {

    /** Convenience factory for offline / zero-token providers. */
    public static LlmResponse of(String responseText) {
        return new LlmResponse(responseText, 0, 0);
    }
}
