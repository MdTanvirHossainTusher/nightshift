package com.nightshift.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.mcp.controller.McpController;
import com.nightshift.mcp.service.McpService;
import com.nightshift.mcp.service.McpServiceImpl;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.*;
import com.nightshift.repository.*;
import com.nightshift.service.agent.FixAgent;
import com.nightshift.service.agent.TriageAgent;
import com.nightshift.service.agent.VerifierAgent;
import com.nightshift.service.publish.PublisherService;
import com.nightshift.service.scan.CodeLocatorService;
import com.nightshift.service.scan.ScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(McpController.class)
@Import({McpServiceImpl.class, NightshiftProperties.class})
class McpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IncidentRepository incidentRepository;

    @MockBean
    private CodeLocationRepository codeLocationRepository;

    @MockBean
    private PatchProposalRepository patchProposalRepository;

    @MockBean
    private PullRequestRepository pullRequestRepository;

    @MockBean
    private IncidentOccurrenceRepository incidentOccurrenceRepository;

    @MockBean
    private ScanService scanService;

    @MockBean
    private CodeLocatorService codeLocatorService;

    @MockBean
    private FixAgent fixAgent;

    @MockBean
    private VerifierAgent verifierAgent;

    @MockBean
    private PublisherService publisherService;

    @MockBean
    private TriageAgent triageAgent;

    private Incident testIncident;
    private CodeLocation testLocation;
    private PatchProposal testProposal;
    private PullRequest testPr;

    @BeforeEach
    void setUp() {
        testIncident = Incident.builder()
                .id(UUID.randomUUID())
                .fingerprint("fp-1234567890abcdef")
                .title("NullPointerException in OrderService")
                .serviceName("order-service")
                .severity(Severity.MAJOR)
                .status(IncidentStatus.NEW)
                .occurrenceCount(5)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .build();

        testLocation = CodeLocation.builder()
                .id(UUID.randomUUID())
                .incident(testIncident)
                .targetRepo("demo/target-repo")
                .filePath("src/main/java/com/example/OrderService.java")
                .startLine(42)
                .endLine(45)
                .confidence(ConfidenceLevel.HIGH)
                .build();

        testProposal = PatchProposal.builder()
                .id(UUID.randomUUID())
                .incident(testIncident)
                .status(PatchStatus.VERIFIED)
                .verifierVerdict(VerifierVerdict.PASS)
                .verifierNotes("Patch verified cleanly")
                .unifiedDiff("--- a/OrderService.java\n+++ b/OrderService.java\n@@ -42 +42 @@\n- old\n+ new")
                .rationale("Add null check")
                .filesChanged(1)
                .linesAdded(1)
                .linesRemoved(1)
                .build();

        testPr = PullRequest.builder()
                .id(UUID.randomUUID())
                .incident(testIncident)
                .patchProposal(testProposal)
                .repoFullName("acme/order-service")
                .branchName("nightshift/fix-major-fp-1234")
                .prNumber(101)
                .prUrl("https://github.com/acme/order-service/pull/101")
                .state(PrState.OPEN)
                .build();
    }

    @Test
    void testInitialize() throws Exception {
        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {}
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2024-11-05"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("nightshift"));
    }

    @Test
    void testToolsList() throws Exception {
        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/list",
                  "params": {}
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools", hasSize(5)))
                .andExpect(jsonPath("$.result.tools[*].name", containsInAnyOrder(
                        "list_incidents", "get_incident", "scan_logs", "propose_patch", "open_pull_request"
                )));
    }

    @Test
    void testToolCallListIncidents() throws Exception {
        when(incidentRepository.findByFilters(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(testIncident)));

        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tools/call",
                  "params": {
                    "name": "list_incidents",
                    "arguments": { "severity": "MAJOR" }
                  }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text", containsString("fp-1234567890abcdef")))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    void testToolCallGetIncident() throws Exception {
        when(incidentRepository.findByFingerprint("fp-1234567890abcdef"))
                .thenReturn(Optional.of(testIncident));
        when(codeLocationRepository.findAllByIncidentId(testIncident.getId()))
                .thenReturn(List.of(testLocation));
        when(patchProposalRepository.findAllByIncidentIdOrderByCreatedAtDesc(testIncident.getId()))
                .thenReturn(List.of(testProposal));
        when(pullRequestRepository.findAllByIncidentId(testIncident.getId()))
                .thenReturn(List.of(testPr));

        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 4,
                  "method": "tools/call",
                  "params": {
                    "name": "get_incident",
                    "arguments": { "fingerprint": "fp-1234567890abcdef" }
                  }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("OrderService.java")));
    }

    @Test
    void testToolCallScanLogs() throws Exception {
        ScanRun scanRun = ScanRun.builder()
                .id(UUID.randomUUID())
                .triggerSource(TriggerSource.MCP)
                .status(ScanStatus.COMPLETED)
                .linesParsed(1500)
                .incidentsNew(2)
                .startedAt(Instant.now())
                .finishedAt(Instant.now())
                .build();

        when(scanService.runScan(TriggerSource.MCP)).thenReturn(scanRun);

        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 5,
                  "method": "tools/call",
                  "params": {
                    "name": "scan_logs",
                    "arguments": {}
                  }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("COMPLETED")));
    }

    @Test
    void testToolCallProposePatch() throws Exception {
        when(incidentRepository.findByFingerprint("fp-1234567890abcdef"))
                .thenReturn(Optional.of(testIncident));
        when(codeLocationRepository.findByIncidentId(testIncident.getId()))
                .thenReturn(Optional.of(testLocation));
        when(fixAgent.proposeFix(testIncident, testLocation))
                .thenReturn(Optional.of(testProposal));
        when(verifierAgent.verify(testProposal, testIncident, testLocation))
                .thenReturn(true);

        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 6,
                  "method": "tools/call",
                  "params": {
                    "name": "propose_patch",
                    "arguments": { "fingerprint": "fp-1234567890abcdef" }
                  }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("Add null check")));
    }

    @Test
    void testToolCallOpenPullRequest() throws Exception {
        when(incidentRepository.findByFingerprint("fp-1234567890abcdef"))
                .thenReturn(Optional.of(testIncident));
        when(patchProposalRepository.findTopByIncidentIdOrderByCreatedAtDesc(testIncident.getId()))
                .thenReturn(Optional.of(testProposal));
        when(publisherService.publish(testIncident, testProposal))
                .thenReturn(testPr);

        String req = """
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "tools/call",
                  "params": {
                    "name": "open_pull_request",
                    "arguments": { "fingerprint": "fp-1234567890abcdef" }
                  }
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString("https://github.com/acme/order-service/pull/101")));
    }

    @Test
    void testGetServerInfoJson() throws Exception {
        mockMvc.perform(get("/mcp")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.server").value("nightshift-mcp-server"))
                .andExpect(jsonPath("$.tools", hasSize(5)));
    }

    @Test
    void testGetSseConnection() throws Exception {
        mockMvc.perform(get("/mcp")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk());
    }
}