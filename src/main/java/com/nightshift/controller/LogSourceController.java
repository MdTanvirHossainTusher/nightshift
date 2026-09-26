package com.nightshift.controller;

import com.nightshift.payload.common.ApiResponse;
import com.nightshift.payload.common.ResponseBuilder;
import com.nightshift.payload.response.LogSourceResponse;
import com.nightshift.repository.LogSourceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Log Sources", description = "Endpoints for inspecting configured log ingestion sources")
@RestController
@RequestMapping("/api/v1/log-sources")
@RequiredArgsConstructor
public class LogSourceController {

    private final LogSourceRepository logSourceRepository;

    @Operation(summary = "List all configured log sources")
    @GetMapping
    public ResponseEntity<ApiResponse<List<LogSourceResponse>>> listLogSources() {
        List<LogSourceResponse> sources = logSourceRepository.findAll().stream()
                .map(LogSourceResponse::from)
                .toList();
        return ResponseBuilder.ok(sources);
    }
}