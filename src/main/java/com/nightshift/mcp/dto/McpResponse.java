package com.nightshift.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpResponse(
        String jsonrpc,
        Object id,
        Object result,
        McpError error
) {
    public static McpResponse success(Object id, Object result) {
        return new McpResponse("2.0", id, result, null);
    }

    public static McpResponse error(Object id, int code, String message) {
        return new McpResponse("2.0", id, null, new McpError(code, message, null));
    }

    public record McpError(int code, String message, Object data) {}

    public record ToolResult(
            List<ContentItem> content,
            @JsonProperty("isError") boolean isError
    ) {
        public static ToolResult text(String text) {
            return new ToolResult(List.of(new ContentItem("text", text)), false);
        }

        public static ToolResult error(String errorMessage) {
            return new ToolResult(List.of(new ContentItem("text", errorMessage)), true);
        }
    }

    public record ContentItem(String type, String text) {}
}