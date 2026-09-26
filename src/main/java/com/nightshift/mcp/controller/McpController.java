package com.nightshift.mcp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.mcp.dto.McpRequest;
import com.nightshift.mcp.dto.McpResponse;
import com.nightshift.mcp.service.McpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "MCP Server", description = "Model Context Protocol JSON-RPC and SSE endpoints for AI agents")
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpService mcpService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "MCP JSON-RPC endpoint for tools execution, listing, and handshake")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handleJsonRpc(@RequestBody JsonNode rawRequest) {
        if (rawRequest.isArray()) {
            List<McpResponse> responses = new ArrayList<>();
            for (JsonNode item : rawRequest) {
                McpRequest req = objectMapper.convertValue(item, McpRequest.class);
                responses.add(mcpService.handleRequest(req));
            }
            return ResponseEntity.ok(responses);
        } else {
            McpRequest req = objectMapper.convertValue(rawRequest, McpRequest.class);
            McpResponse response = mcpService.handleRequest(req);
            return ResponseEntity.ok(response);
        }
    }

    @Operation(summary = "MCP Server-Sent Events (SSE) streaming endpoint")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connectSse() {
        SseEmitter emitter = new SseEmitter(180_000L);
        try {
            // MCP SSE handshake sends initial 'endpoint' event with target URI
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/mcp"));
        } catch (IOException e) {
            log.warn("Failed to send initial MCP SSE endpoint event: {}", e.getMessage());
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Operation(summary = "MCP server info and registered tools descriptor")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getServerInfo() {
        return ResponseEntity.ok(Map.of(
                "server", "nightshift-mcp-server",
                "version", "0.1.0",
                "endpoint", "/mcp",
                "tools", mcpService.getTools()
        ));
    }
}