package com.nightshift.service.llm;

import com.nightshift.config.properties.NightshiftProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the active {@link LlmClient} at start-up and exposes it via {@link #resolve()}.
 *
 * <p>Selection logic (in priority order):
 * <ol>
 *   <li>Use the client whose {@link LlmClient#provider()} matches
 *       {@code nightshift.llm.provider} and whose {@link LlmClient#isAvailable()}
 *       returns {@code true}.</li>
 *   <li>If the configured provider is unavailable (e.g. missing API key), fall back
 *       to the {@code heuristic} client and log a warning.</li>
 *   <li>If even {@code heuristic} is missing (should never happen), throw at start-up.</li>
 * </ol>
 *
 * <p>Callers should use {@link #resolve(String)} when they need a specific provider
 * by name (e.g. in tests or the evaluation harness).
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);
    private static final String FALLBACK = "heuristic";

    private final Map<String, LlmClient> byProvider;
    private final NightshiftProperties properties;

    public LlmClientRegistry(List<LlmClient> clients,
                              NightshiftProperties properties) {
        this.byProvider = clients.stream()
                .collect(Collectors.toMap(LlmClient::provider, Function.identity()));
        this.properties = properties;
    }

    /**
     * Returns the active {@link LlmClient} for the current configuration.
     * Falls back to {@code heuristic} when the configured provider is unavailable.
     */
    public LlmClient resolve() {
        String configuredProvider = properties.getLlm() != null && properties.getLlm().getProvider() != null
                ? properties.getLlm().getProvider()
                : FALLBACK;
        LlmClient client = byProvider.get(configuredProvider);
        if (client != null && client.isAvailable()) {
            return client;
        }
        if (client != null) {
            log.warn("LLM provider '{}' is configured but not available (missing API key?); " +
                     "falling back to '{}'", configuredProvider, FALLBACK);
        } else {
            log.warn("Unknown LLM provider '{}' in nightshift.llm.provider; " +
                     "falling back to '{}'", configuredProvider, FALLBACK);
        }
        LlmClient fallback = byProvider.get(FALLBACK);
        if (fallback == null) {
            throw new IllegalStateException(
                    "No '" + FALLBACK + "' LlmClient bean found; cannot fall back");
        }
        return fallback;
    }

    /**
     * Returns the {@link LlmClient} for the given provider name.
     *
     * @throws IllegalArgumentException when no client with that name is registered
     */
    public LlmClient resolve(String provider) {
        LlmClient client = byProvider.get(provider);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No LlmClient registered for provider '" + provider + "'");
        }
        return client;
    }
}
