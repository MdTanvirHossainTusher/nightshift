package com.nightshift.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Component
public class AnthropicLlmClient implements LlmClient {

    private final NightshiftProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public AnthropicLlmClient(NightshiftProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    public AnthropicLlmClient(NightshiftProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public boolean isAvailable() {
        String key = properties.getLlm().getAnthropic().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isAvailable()) {
            throw new ExternalServiceException("Anthropic", "API key is not configured");
        }

        String apiKey = properties.getLlm().getAnthropic().getApiKey();
        String model = properties.getLlm().getAnthropic().getModel();
        String baseUrl = properties.getLlm().getAnthropic().getBaseUrl();
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "messages" : baseUrl + "/messages";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", 4096);
            root.put("temperature", 0.1);

            String systemPrompt = loadPrompt(request.systemPromptPath());
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                root.put("system", systemPrompt);
            }

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.userPrompt());

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new ExternalServiceException("Anthropic", "HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
            }

            JsonNode responseJson = objectMapper.readTree(httpResponse.body());
            String content = responseJson.path("content").path(0).path("text").asText("");
            int inputTokens = responseJson.path("usage").path("input_tokens").asInt(0);
            int outputTokens = responseJson.path("usage").path("output_tokens").asInt(0);

            return new LlmResponse(content, inputTokens, outputTokens);
        } catch (ExternalServiceException ese) {
            throw ese;
        } catch (Exception e) {
            throw new ExternalServiceException("Anthropic", "Request failed: " + e.getMessage(), e);
        }
    }

    private String loadPrompt(String path) {
        if (path == null || path.isBlank()) return null;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not load prompt {}: {}", path, e.getMessage());
            return null;
        }
    }
}