package com.nightshift.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpRequest(
        String jsonrpc,
        Object id,
        String method,
        JsonNode params
) {}