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

class OpenAiLlmClientTest {

    private NightshiftProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new NightshiftProperties();
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        client = new OpenAiLlmClient(properties, objectMapper, httpClient);
    }

    @Test
    void testIsAvailableFalseWhenKeyMissing() {
        properties.getLlm().getOpenai().setApiKey("");
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void testIsAvailableTrueWhenKeyPresent() {
        properties.getLlm().getOpenai().setApiKey("test-key-not-real");
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void testCompleteSuccess() throws Exception {
        properties.getLlm().getOpenai().setApiKey("test-key-not-real");
        properties.getLlm().getOpenai().setModel("gpt-4o-mini");

        String fakeResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "{\\"severity\\": \\"CRITICAL\\"}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 120,
                    "completion_tokens": 45
                  }
                }
                """;

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        doReturn(fakeResponse).when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        LlmResponse response = client.complete(LlmRequest.of("System prompt error", "Check incident"));

        assertThat(response.responseText()).isEqualTo("{\"severity\": \"CRITICAL\"}");
        assertThat(response.tokensIn()).isEqualTo(120);
        assertThat(response.tokensOut()).isEqualTo(45);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).contains("/chat/completions");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-key-not-real");
    }

    @Test
    void testCompleteThrowsOnNon2xx() throws Exception {
        properties.getLlm().getOpenai().setApiKey("test-key-not-real");

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(401);
        doReturn("{\"error\": \"invalid_api_key\"}").when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.complete(LlmRequest.of("prompt")))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 401");
    }
}