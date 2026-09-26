package com.nightshift.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import(NightshiftProperties.class)
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanupSystemProperties() {
        System.clearProperty("OPENAI_API_KEY");
        System.clearProperty("GEMINI_API_KEY");
        System.clearProperty("ANTHROPIC_API_KEY");
        System.clearProperty("GITHUB_TOKEN");
    }

    @Test
    void getConfig_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.provider").isNotEmpty())
                .andExpect(jsonPath("$.data.git_repo").isNotEmpty());
    }

    @Test
    void updateConfig_updatesProviderAndModel() throws Exception {
        ConfigController.ConfigUpdateRequest req = new ConfigController.ConfigUpdateRequest();
        req.setProvider("openai");
        req.setModel("gpt-4o-mini");
        req.setApiKey("sk-test-key-12345");
        req.setGithubToken("ghp_test_pat_token");
        req.setDryRun(false);

        mockMvc.perform(post("/api/v1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.provider").value("openai"))
                .andExpect(jsonPath("$.data.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.data.has_openai_key").value(true))
                .andExpect(jsonPath("$.data.has_github_token").value(true))
                .andExpect(jsonPath("$.data.dry_run").value(false));
    }
}
