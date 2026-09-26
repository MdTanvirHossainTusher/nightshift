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
public class OpenAiLlmClient implements LlmClient {

    private final NightshiftProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiLlmClient(NightshiftProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    public OpenAiLlmClient(NightshiftProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return "openai";
    }

    private String getEffectiveApiKey() {
        String key = properties.getLlm().getOpenai().getApiKey();
        if (key != null && !key.isBlank()) return key.strip();
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey.strip();
        String sysKey = System.getProperty("OPENAI_API_KEY");
        if (sysKey != null && !sysKey.isBlank()) return sysKey.strip();
        return "";
    }

    @Override
    public boolean isAvailable() {
        return !getEffectiveApiKey().isBlank();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String apiKey = getEffectiveApiKey();
        if (apiKey.isBlank()) {
            throw new ExternalServiceException("OpenAI", "API key is not configured");
        }

        String model = properties.getLlm().getOpenai().getModel();
        String baseUrl = properties.getLlm().getOpenai().getBaseUrl();
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0.1);

            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            root.set("response_format", responseFormat);

            ArrayNode messages = root.putArray("messages");

            String systemPrompt = loadPrompt(request.systemPromptPath());
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sysMsg = messages.addObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
            }

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.userPrompt());

            String requestBody = objectMapper.writeValueAsString(root);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new ExternalServiceException("OpenAI", "HTTP " + httpResponse.statusCode() + ": " + httpResponse.body());
            }

            JsonNode responseJson = objectMapper.readTree(httpResponse.body());
            String content = responseJson.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = responseJson.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = responseJson.path("usage").path("completion_tokens").asInt(0);

            return new LlmResponse(content, promptTokens, completionTokens);
        } catch (ExternalServiceException ese) {
            throw ese;
        } catch (Exception e) {
            throw new ExternalServiceException("OpenAI", "Request failed: " + e.getMessage(), e);
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