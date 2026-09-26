package com.nightshift.service.llm;

/**
 * SPI for all LLM back-ends understood by Nightshift.
 *
 * <p>Each implementation is a Spring bean.  {@link LlmClientRegistry} picks the
 * active one at start-up based on {@code nightshift.llm.provider}.
 *
 * <p>Implementations must be safe to call from multiple threads; Spring beans are
 * singletons by default.
 */
public interface LlmClient {

    /**
     * The provider name this client handles, e.g. {@code "heuristic"}, {@code "openai"}.
     * Must match the values accepted by {@code nightshift.llm.provider}.
     */
    String provider();

    /**
     * Sends {@code request} to the back-end and returns the response.
     *
     * <p>On a parse failure the caller should retry once with a repair instruction
     * appended to the user prompt, then record {@code LLM_RESPONSE_UNPARSEABLE} if
     * the second attempt also fails.
     *
     * @param request the prompt payload
     * @return the model response
     * @throws com.nightshift.exception.ExternalServiceException when a real API call
     *         fails irrecoverably (network error, authentication failure, etc.)
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Returns true when this provider can currently serve requests.
     *
     * <p>For offline providers (e.g. {@code heuristic}) this always returns
     * {@code true}. For API-backed providers it returns {@code false} when the
     * configured API key is absent or blank, so the registry can fall back to
     * {@code heuristic} gracefully.
     */
    boolean isAvailable();

    /**
     * The model name or version used by this client.
     */
    default String model() {
        return "";
    }
}
