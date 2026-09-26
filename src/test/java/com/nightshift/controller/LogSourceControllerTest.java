package com.nightshift.controller;

import com.nightshift.model.entity.LogSource;
import com.nightshift.repository.LogSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LogSourceController.class)
class LogSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LogSourceRepository logSourceRepository;

    @Test
    void listLogSources_returns200WithApiResponse() throws Exception {
        LogSource source = LogSource.builder()
                .id(UUID.randomUUID())
                .name("farmer-service")
                .serviceName("farmer-service")
                .rootPath("demo/logs/farmer-service")
                .fileGlob("*.log")
                .targetRepo("com.example.farmer")
                .enabled(true)
                .createdAt(Instant.now())
                .build();

        when(logSourceRepository.findAll()).thenReturn(List.of(source));

        mockMvc.perform(get("/api/v1/log-sources").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("farmer-service"))
                .andExpect(jsonPath("$.data[0].enabled").value(true));
    }
}