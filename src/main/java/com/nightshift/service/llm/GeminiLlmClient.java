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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Component
public class GeminiLlmClient implements LlmClient {

    private final NightshiftProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiLlmClient(NightshiftProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    public GeminiLlmClient(NightshiftProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        String key = properties.getLlm().getGemini().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isAvailable()) {
            throw new ExternalServiceException("Gemini", "API key is not configured");
        }

        String apiKey = properties.getLlm().getGemini().getApiKey();
        String model = properties.getLlm().getGemini().getModel();
        String baseUrl = properties.getLlm().getGemini().getBaseUrl();
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String endpoint = cleanBase + "/v1beta/models/" + model + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        try {
            ObjectNode root = objectMapper.createObjectNode();

            String systemPrompt = loadPrompt(request.systemPromptPath());
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sysInstruction = root.putObject("systemInstruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                parts.addObject().put("text", systemPrompt);
            }

            ArrayNode contents = root.putArray("contents");
            ObjectNode contentObj = contents.addObject();
            contentObj.put("role", "user");
            ArrayNode parts = contentObj.putArray("parts");
            parts.addObject().put("text", request.userPrompt());

            ObjectNode genConfig = root.putObject("generationConfig");
            genConfig.put("responseMimeType", "application/json");
            genConfig.put("temperature", 0.1);

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new ExternalServiceException("Gemini", "HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
            }

            JsonNode responseJson = objectMapper.readTree(httpResponse.body());
            String text = responseJson.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            int promptTokens = responseJson.path("usageMetadata").path("promptTokenCount").asInt(0);
            int candidateTokens = responseJson.path("usageMetadata").path("candidatesTokenCount").asInt(0);

            return new LlmResponse(text, promptTokens, candidateTokens);
        } catch (ExternalServiceException ese) {
            throw ese;
        } catch (Exception e) {
            throw new ExternalServiceException("Gemini", "Request failed: " + e.getMessage(), e);
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