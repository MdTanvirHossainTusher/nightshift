package com.nightshift.mcp.service;

import com.nightshift.mcp.dto.McpRequest;
import com.nightshift.mcp.dto.McpResponse;
import com.nightshift.mcp.dto.McpTool;

import java.util.List;

/**
 * Service handling MCP (Model Context Protocol) JSON-RPC requests and tool executions.
 */
public interface McpService {

    /**
     * Handles an incoming MCP JSON-RPC 2.0 request and returns the response.
     */
    McpResponse handleRequest(McpRequest request);

    /**
     * Returns the list of registered MCP tools.
     */
    List<McpTool> getTools();
}