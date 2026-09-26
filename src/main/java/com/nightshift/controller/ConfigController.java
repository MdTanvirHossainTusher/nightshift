package com.nightshift.controller;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.payload.common.ApiResponse;
import com.nightshift.payload.common.ResponseBuilder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "Configuration", description = "Endpoints for configuring active LLM provider, API keys, and repository")
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final NightshiftProperties props;

    @Operation(summary = "Get current LLM and publishing configuration")
    @GetMapping
    public ResponseEntity<ApiResponse<ConfigResponse>> getConfig() {
        ConfigResponse resp = buildConfigResponse();
        return ResponseBuilder.ok(resp);
    }

    @Operation(summary = "Update active LLM provider, API keys, and publishing options")
    @PostMapping
    public ResponseEntity<ApiResponse<ConfigResponse>> updateConfig(@RequestBody ConfigUpdateRequest req) {
        if (req.getProvider() != null && !req.getProvider().isBlank()) {
            props.getLlm().setProvider(req.getProvider().toLowerCase().trim());
            log.info("Active LLM provider switched to: {}", props.getLlm().getProvider());
        }

        String provider = props.getLlm().getProvider();
        if (req.getModel() != null && !req.getModel().isBlank()) {
            String model = req.getModel().trim();
            if ("gemini".equalsIgnoreCase(provider)) {
                props.getLlm().getGemini().setModel(model);
            } else if ("openai".equalsIgnoreCase(provider)) {
                props.getLlm().getOpenai().setModel(model);
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                props.getLlm().getAnthropic().setModel(model);
            }
            log.info("Model configured dynamically for provider {}: {}", provider, model);
        }

        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            String baseUrl = req.getBaseUrl().trim();
            if ("openai".equalsIgnoreCase(provider)) {
                props.getLlm().getOpenai().setBaseUrl(baseUrl);
            } else if ("gemini".equalsIgnoreCase(provider)) {
                props.getLlm().getGemini().setBaseUrl(baseUrl);
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                props.getLlm().getAnthropic().setBaseUrl(baseUrl);
            }
            log.info("Base URL configured dynamically for provider {}: {}", provider, baseUrl);
        }

        if (req.getApiKey() != null) {
            String key = req.getApiKey().trim();
            if ("gemini".equalsIgnoreCase(provider)) {
                props.getLlm().getGemini().setApiKey(key.isEmpty() ? null : key);
                if (key.isEmpty()) System.clearProperty("GEMINI_API_KEY"); else System.setProperty("GEMINI_API_KEY", key);
            } else if ("openai".equalsIgnoreCase(provider)) {
                props.getLlm().getOpenai().setApiKey(key.isEmpty() ? null : key);
                if (key.isEmpty()) System.clearProperty("OPENAI_API_KEY"); else System.setProperty("OPENAI_API_KEY", key);
            } else if ("anthropic".equalsIgnoreCase(provider)) {
                props.getLlm().getAnthropic().setApiKey(key.isEmpty() ? null : key);
                if (key.isEmpty()) System.clearProperty("ANTHROPIC_API_KEY"); else System.setProperty("ANTHROPIC_API_KEY", key);
            }
            log.info("API key configured dynamically for provider: {}", provider);
        }

        if (req.getGitRepo() != null && !req.getGitRepo().isBlank()) {
            props.getGit().setRepo(req.getGitRepo().trim());
        }

        if (req.getGithubToken() != null) {
            String token = req.getGithubToken().trim();
            if (token.isEmpty()) {
                System.clearProperty("GITHUB_TOKEN");
            } else {
                System.setProperty("GITHUB_TOKEN", token);
            }
        }

        if (req.getDryRun() != null) {
            props.getPublish().setDryRun(req.getDryRun());
        }

        return ResponseBuilder.ok(buildConfigResponse());
    }

    private static boolean isSet(String val) {
        return val != null && !val.trim().isEmpty();
    }

    private boolean hasKey(String propKey, String envVarName) {
        if (isSet(propKey)) return true;
        if (isSet(System.getenv(envVarName))) return true;
        if (isSet(System.getProperty(envVarName))) return true;
        return false;
    }

    private ConfigResponse buildConfigResponse() {
        boolean hasOpenAi = hasKey(props.getLlm().getOpenai().getApiKey(), "OPENAI_API_KEY");
        boolean hasGemini = hasKey(props.getLlm().getGemini().getApiKey(), "GEMINI_API_KEY");
        boolean hasAnthropic = hasKey(props.getLlm().getAnthropic().getApiKey(), "ANTHROPIC_API_KEY");
        boolean hasGithubToken = hasKey(null, "GITHUB_TOKEN");

        String repo = props.getGit() != null && !props.getGit().getRepo().isBlank()
                ? props.getGit().getRepo()
                : "MdTanvirHossainTusher/nightshift";

        String baseBranch = props.getGit() != null && !props.getGit().getBaseBranch().isBlank()
                ? props.getGit().getBaseBranch()
                : "main";

        boolean dryRun = props.getPublish() == null || props.getPublish().isDryRun();

        String activeModel = switch (props.getLlm().getProvider().toLowerCase()) {
            case "gemini" -> props.getLlm().getGemini().getModel();
            case "openai" -> props.getLlm().getOpenai().getModel();
            case "anthropic" -> props.getLlm().getAnthropic().getModel();
            default -> "heuristic-rules";
        };

        return new ConfigResponse(
                props.getLlm().getProvider(),
                activeModel,
                props.getLlm().getOpenai().getModel(),
                props.getLlm().getGemini().getModel(),
                props.getLlm().getAnthropic().getModel(),
                hasOpenAi,
                hasGemini,
                hasAnthropic,
                hasGithubToken,
                repo,
                baseBranch,
                dryRun
        );
    }

    @Data
    public static class ConfigUpdateRequest {
        private String provider;
        private String apiKey;
        private String model;
        private String baseUrl;
        private String gitRepo;
        private String githubToken;
        private Boolean dryRun;
    }

    public record ConfigResponse(
            String provider,
            String model,
            String openaiModel,
            String geminiModel,
            String anthropicModel,
            boolean hasOpenaiKey,
            boolean hasGeminiKey,
            boolean hasAnthropicKey,
            boolean hasGithubToken,
            String gitRepo,
            String baseBranch,
            boolean dryRun
    ) {}
}
