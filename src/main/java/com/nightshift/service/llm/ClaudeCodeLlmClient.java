package com.nightshift.service.llm;

import com.nightshift.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ClaudeCodeLlmClient implements LlmClient {

    @Override
    public String provider() {
        return "claude-code";
    }

    @Override
    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder("claude", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Claude CLI not available on system PATH: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!isAvailable()) {
            throw new ExternalServiceException("ClaudeCode", "Claude CLI executable 'claude' is not available on PATH");
        }

        StringBuilder fullPrompt = new StringBuilder();
        String systemPrompt = loadPrompt(request.systemPromptPath());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            fullPrompt.append("System Instructions:\n").append(systemPrompt).append("\n\n");
        }
        fullPrompt.append(request.userPrompt());

        try {
            Process process = new ProcessBuilder("claude", "-p", fullPrompt.toString(), "--output-format", "json")
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ExternalServiceException("ClaudeCode", "Execution timed out after 60s");
            }

            if (process.exitValue() != 0) {
                String errorOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new ExternalServiceException("ClaudeCode", "CLI exited with code " + process.exitValue() + ": " + errorOutput);
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new LlmResponse(output.trim(), 0, 0);
        } catch (ExternalServiceException ese) {
            throw ese;
        } catch (Exception e) {
            throw new ExternalServiceException("ClaudeCode", "Failed to run claude CLI: " + e.getMessage(), e);
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