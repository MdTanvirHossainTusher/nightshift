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

class AnthropicLlmClientTest {

    private NightshiftProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private AnthropicLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new NightshiftProperties();
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        client = new AnthropicLlmClient(properties, objectMapper, httpClient);
    }

    @Test
    void testIsAvailableFalseWhenKeyMissing() {
        properties.getLlm().getAnthropic().setApiKey("");
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void testIsAvailableTrueWhenKeyPresent() {
        properties.getLlm().getAnthropic().setApiKey("test-key-not-real");
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void testCompleteSuccess() throws Exception {
        properties.getLlm().getAnthropic().setApiKey("test-key-not-real");
        properties.getLlm().getAnthropic().setModel("claude-sonnet-4-5");

        String fakeResponse = """
                {
                  "content": [
                    { "type": "text", "text": "{\\"severity\\": \\"BLOCKER\\"}" }
                  ],
                  "usage": {
                    "input_tokens": 150,
                    "output_tokens": 60
                  }
                }
                """;

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        doReturn(fakeResponse).when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        LlmResponse response = client.complete(LlmRequest.of("Fix memory leak"));

        assertThat(response.responseText()).isEqualTo("{\"severity\": \"BLOCKER\"}");
        assertThat(response.tokensIn()).isEqualTo(150);
        assertThat(response.tokensOut()).isEqualTo(60);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).contains("/messages");
        assertThat(request.headers().firstValue("x-api-key")).contains("test-key-not-real");
        assertThat(request.headers().firstValue("anthropic-version")).contains("2023-06-01");
    }

    @Test
    void testCompleteThrowsOnNon2xx() throws Exception {
        properties.getLlm().getAnthropic().setApiKey("test-key-not-real");

        HttpResponse<?> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(500);
        doReturn("Internal error").when(httpResponse).body();
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.complete(LlmRequest.of("prompt")))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("HTTP 500");
    }
}