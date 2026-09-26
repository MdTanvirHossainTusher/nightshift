package com.nightshift.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record McpTool(
        String name,
        String description,
        @JsonProperty("inputSchema") Map<String, Object> inputSchema
) {}