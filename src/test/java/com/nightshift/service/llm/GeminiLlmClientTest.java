package com.nightshift.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeminiLlmClientTest {

    private NightshiftProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private GeminiLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new NightshiftProperties();
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        client = new GeminiLlmClient(properties, objectMapper, httpClient);
    }

    @Test
    void testIsAvailableFalseWhenKeyMissing() {
        properties.getLlm().getGemini().setApiKey("");
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void testIsAvailableTrueWhenKeyPresent() {
        properties.getLlm().getGemini().setApiKey("test-key-not-real");
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void testCompleteSuccess() throws Exception {
        properties.getLlm().getGemini().setApiKey("test-key-not-real");
        properties.getLlm().getGemini().setModel("gemini-2.0-flash");

        String fakeResponse = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "{\\"severity\\": \\"MAJOR\\"}" }
                        ]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 200,
                    "candidatesTokenCount": 80
                  }
                }
                """;

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        doReturn(fakeResponse).when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        LlmResponse response = client.complete(LlmRequest.of("Analyze stack trace"));

        assertThat(response.responseText()).isEqualTo("{\"severity\": \"MAJOR\"}");
        assertThat(response.tokensIn()).isEqualTo(200);
        assertThat(response.tokensOut()).isEqualTo(80);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).contains("generateContent?key=test-key-not-real");
    }

    @Test
    void testCompleteThrowsOnNon2xx() throws Exception {
        properties.getLlm().getGemini().setApiKey("test-key-not-real");

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(403);
        doReturn("{\"error\": \"permission_denied\"}").when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.complete(LlmRequest.of("prompt")))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 403");
    }
}