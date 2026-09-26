package com.nightshift.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.mcp.dto.McpRequest;
import com.nightshift.mcp.dto.McpResponse;
import com.nightshift.mcp.dto.McpTool;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.payload.response.CodeLocationResponse;
import com.nightshift.payload.response.IncidentDetailResponse;
import com.nightshift.payload.response.IncidentOccurrenceResponse;
import com.nightshift.payload.response.IncidentResponse;
import com.nightshift.payload.response.PatchProposalResponse;
import com.nightshift.payload.response.PullRequestResponse;
import com.nightshift.payload.response.ScanRunResponse;
import com.nightshift.repository.*;
import com.nightshift.service.agent.FixAgent;
import com.nightshift.service.agent.TriageAgent;
import com.nightshift.service.agent.VerifierAgent;
import com.nightshift.service.publish.PublisherService;
import com.nightshift.service.scan.CodeLocatorService;
import com.nightshift.service.scan.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpServiceImpl implements McpService {

    private final IncidentRepository incidentRepository;
    private final CodeLocationRepository codeLocationRepository;
    private final PatchProposalRepository patchProposalRepository;
    private final PullRequestRepository pullRequestRepository;
    private final IncidentOccurrenceRepository incidentOccurrenceRepository;
    private final ScanService scanService;
    private final CodeLocatorService codeLocatorService;
    private final FixAgent fixAgent;
    private final VerifierAgent verifierAgent;
    private final PublisherService publisherService;
    private final TriageAgent triageAgent;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public McpResponse handleRequest(McpRequest request) {
        if (request == null || request.method() == null) {
            return McpResponse.error(null, -32600, "Invalid Request: method is missing");
        }

        Object id = request.id();
        String method = request.method();
        log.info("Handling MCP request: id={}, method={}", id, method);

        return switch (method) {
            case "initialize" -> McpResponse.success(id, Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "nightshift", "version", "0.1.0")
            ));
            case "notifications/initialized" -> McpResponse.success(id, Map.of());
            case "ping" -> McpResponse.success(id, Map.of());
            case "tools/list" -> McpResponse.success(id, Map.of("tools", getTools()));
            case "tools/call" -> handleToolCall(id, request.params());
            default -> McpResponse.error(id, -32601, "Method not found: " + method);
        };
    }

    private McpResponse handleToolCall(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return McpResponse.error(id, -32602, "Invalid params: 'name' is required");
        }

        String toolName = params.get("name").asText();
        JsonNode arguments = params.get("arguments");

        log.info("Executing MCP tool: name={}", toolName);

        try {
            McpResponse.ToolResult result = switch (toolName) {
                case "list_incidents" -> callListIncidents(arguments);
                case "get_incident" -> callGetIncident(arguments);
                case "scan_logs" -> callScanLogs(arguments);
                case "propose_patch" -> callProposePatch(arguments);
                case "open_pull_request" -> callOpenPullRequest(arguments);
                default -> McpResponse.ToolResult.error("Unknown tool: " + toolName);
            };

            return McpResponse.success(id, result);
        } catch (Exception e) {
            log.error("Error executing MCP tool {}: {}", toolName, e.getMessage(), e);
            return McpResponse.success(id, McpResponse.ToolResult.error("Tool execution failed: " + e.getMessage()));
        }
    }

    private McpResponse.ToolResult callListIncidents(JsonNode arguments) {
        IncidentStatus status = null;
        Severity severity = null;

        if (arguments != null) {
            if (arguments.hasNonNull("status")) {
                try {
                    status = IncidentStatus.valueOf(arguments.get("status").asText().toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }
            if (arguments.hasNonNull("severity")) {
                try {
                    severity = Severity.valueOf(arguments.get("severity").asText().toUpperCase());
                } catch (IllegalArgumentException ignored) {}
            }
        }

        List<Incident> incidents = incidentRepository
                .findByFilters(status, severity, PageRequest.of(0, 100))
                .getContent();

        List<IncidentResponse> responses = incidents.stream()
                .map(IncidentResponse::from)
                .toList();

        return McpResponse.ToolResult.text(toJsonString(responses));
    }

    private McpResponse.ToolResult callGetIncident(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("fingerprint")) {
            return McpResponse.ToolResult.error("Missing required parameter: 'fingerprint'");
        }

        String fingerprint = arguments.get("fingerprint").asText();
        Optional<Incident> opt = incidentRepository.findByFingerprint(fingerprint);
        if (opt.isEmpty()) {
            return McpResponse.ToolResult.error("Incident not found for fingerprint: " + fingerprint);
        }

        Incident incident = opt.get();
        UUID id = incident.getId();

        List<CodeLocationResponse> codeLocations = codeLocationRepository.findAllByIncidentId(id).stream()
                .map(CodeLocationResponse::from)
                .toList();

        List<PatchProposalResponse> patchProposals = patchProposalRepository.findAllByIncidentIdOrderByCreatedAtDesc(id).stream()
                .map(PatchProposalResponse::from)
                .toList();

        List<PullRequestResponse> pullRequests = pullRequestRepository.findAllByIncidentId(id).stream()
                .map(PullRequestResponse::from)
                .toList();

        List<IncidentOccurrenceResponse> occurrences = incidentOccurrenceRepository
                .findTopByIncidentId(id, PageRequest.of(0, 10)).stream()
                .map(IncidentOccurrenceResponse::from)
                .toList();

        IncidentDetailResponse detail = IncidentDetailResponse.of(
                incident, codeLocations, patchProposals, pullRequests, occurrences
        );
        return McpResponse.ToolResult.text(toJsonString(detail));
    }

    private McpResponse.ToolResult callScanLogs(JsonNode arguments) {
        ScanRun run = scanService.runScan(TriggerSource.MCP);
        ScanRunResponse response = ScanRunResponse.from(run);
        return McpResponse.ToolResult.text(toJsonString(response));
    }

    private McpResponse.ToolResult callProposePatch(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("fingerprint")) {
            return McpResponse.ToolResult.error("Missing required parameter: 'fingerprint'");
        }

        String fingerprint = arguments.get("fingerprint").asText();
        Incident incident = incidentRepository.findByFingerprint(fingerprint)
                .orElse(null);

        if (incident == null) {
            return McpResponse.ToolResult.error("Incident not found for fingerprint: " + fingerprint);
        }

        // Triage if not triaged yet
        if (incident.getSeverity() == null) {
            triageAgent.triage(incident, null);
        }

        CodeLocation location = codeLocationRepository.findByIncidentId(incident.getId())
                .orElseGet(() -> codeLocatorService.locate(incident).orElse(null));

        if (location == null) {
            return McpResponse.ToolResult.error("Could not locate application source code for incident: " + fingerprint);
        }

        PatchProposal proposal = fixAgent.proposeFix(incident, location)
                .orElse(null);

        if (proposal == null) {
            return McpResponse.ToolResult.error("Could not generate a valid patch within safety constraints.");
        }

        boolean verified = verifierAgent.verify(proposal, incident, location);
        PatchProposalResponse response = PatchProposalResponse.from(proposal);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposal", response);
        result.put("verified", verified);
        return McpResponse.ToolResult.text(toJsonString(result));
    }

    private McpResponse.ToolResult callOpenPullRequest(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("fingerprint")) {
            return McpResponse.ToolResult.error("Missing required parameter: 'fingerprint'");
        }

        String fingerprint = arguments.get("fingerprint").asText();
        Incident incident = incidentRepository.findByFingerprint(fingerprint)
                .orElse(null);

        if (incident == null) {
            return McpResponse.ToolResult.error("Incident not found for fingerprint: " + fingerprint);
        }

        PatchProposal proposal = patchProposalRepository
                .findTopByIncidentIdOrderByCreatedAtDesc(incident.getId())
                .orElse(null);

        if (proposal == null) {
            // Automatically generate patch proposal if not present
            CodeLocation location = codeLocationRepository.findByIncidentId(incident.getId())
                    .orElseGet(() -> codeLocatorService.locate(incident).orElse(null));
            if (location == null) {
                return McpResponse.ToolResult.error("Could not locate source code to generate patch for: " + fingerprint);
            }
            proposal = fixAgent.proposeFix(incident, location).orElse(null);
            if (proposal == null) {
                return McpResponse.ToolResult.error("Could not generate patch proposal for: " + fingerprint);
            }
            verifierAgent.verify(proposal, incident, location);
        }

        PullRequest pr = publisherService.publish(incident, proposal);
        PullRequestResponse response = PullRequestResponse.from(pr);
        return McpResponse.ToolResult.text(toJsonString(response));
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }

    @Override
    public List<McpTool> getTools() {
        return List.of(
                new McpTool(
                        "list_incidents",
                        "List all detected incidents with optional status and severity filtering",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "status", Map.of(
                                                "type", "string",
                                                "description", "Optional status filter (e.g. NEW, TRIAGED, MUTED, RESOLVED)"
                                        ),
                                        "severity", Map.of(
                                                "type", "string",
                                                "description", "Optional minimum severity filter (BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)"
                                        )
                                )
                        )
                ),
                new McpTool(
                        "get_incident",
                        "Get detailed incident diagnostic data, occurrences, code location, and patch proposals by fingerprint",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "fingerprint", Map.of(
                                                "type", "string",
                                                "description", "The unique SHA-256 fingerprint of the incident"
                                        )
                                ),
                                "required", List.of("fingerprint")
                        )
                ),
                new McpTool(
                        "scan_logs",
                        "Trigger an immediate overnight-style incremental log scan over all configured log sources",
                        Map.of(
                                "type", "object",
                                "properties", Map.of()
                        )
                ),
                new McpTool(
                        "propose_patch",
                        "Locate code, generate bounded fix diff, and run adversarial verification for an incident",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "fingerprint", Map.of(
                                                "type", "string",
                                                "description", "The unique SHA-256 fingerprint of the incident to generate a fix for"
                                        )
                                ),
                                "required", List.of("fingerprint")
                        )
                ),
                new McpTool(
                        "open_pull_request",
                        "Publish a verified patch proposal as a GitHub pull request on a dedicated fix branch",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "fingerprint", Map.of(
                                                "type", "string",
                                                "description", "The unique SHA-256 fingerprint of the incident to publish a pull request for"
                                        )
                                ),
                                "required", List.of("fingerprint")
                        )
                )
        );
    }
}