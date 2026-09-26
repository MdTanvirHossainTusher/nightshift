package com.nightshift.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.constant.code.SuccessCodes;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.*;
import com.nightshift.payload.request.MuteIncidentRequest;
import com.nightshift.repository.*;
import com.nightshift.service.agent.FixAgent;
import com.nightshift.service.agent.TriageAgent;
import com.nightshift.service.agent.VerifierAgent;
import com.nightshift.service.publish.PublisherService;
import com.nightshift.service.scan.CodeLocatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IncidentRepository incidentRepository;

    @MockitoBean
    private CodeLocationRepository codeLocationRepository;

    @MockitoBean
    private PatchProposalRepository patchProposalRepository;

    @MockitoBean
    private PullRequestRepository pullRequestRepository;

    @MockitoBean
    private IncidentOccurrenceRepository incidentOccurrenceRepository;

    @MockitoBean
    private AgentStepRepository agentStepRepository;

    @MockitoBean
    private TriageAgent triageAgent;

    @MockitoBean
    private CodeLocatorService codeLocatorService;

    @MockitoBean
    private FixAgent fixAgent;

    @MockitoBean
    private VerifierAgent verifierAgent;

    @MockitoBean
    private PublisherService publisherService;

    private Incident incident;
    private UUID incidentId;

    @BeforeEach
    void setUp() {
        incidentId = UUID.randomUUID();
        incident = Incident.builder()
                .id(incidentId)
                .fingerprint("fp-controller-test")
                .title("Connection pool exhausted")
                .serviceName("farmer-service")
                .loggerName("com.example.farmer.FarmerSyncService")
                .logLevel("ERROR")
                .normalizedMessage("Hikari pool connection unavailable")
                .occurrenceCount(15)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.NEW)
                .severity(Severity.CRITICAL)
                .build();
    }

    @Test
    void listIncidents_returns200() throws Exception {
        when(incidentRepository.findByFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(incident)));
        when(incidentOccurrenceRepository.findLatestScanRunPerIncident(any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].title").value("Connection pool exhausted"));
    }

    @Test
    void getIncident_returnsDetail200() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(codeLocationRepository.findAllByIncidentId(incidentId)).thenReturn(List.of());
        when(patchProposalRepository.findAllByIncidentIdOrderByCreatedAtDesc(incidentId)).thenReturn(List.of());
        when(pullRequestRepository.findAllByIncidentId(incidentId)).thenReturn(List.of());
        when(incidentOccurrenceRepository.findTopByIncidentId(eq(incidentId), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/incidents/" + incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(incidentId.toString()));
    }

    @Test
    void triageIncident_returns200IncidentTriaged() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/triage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(SuccessCodes.INCIDENT_TRIAGED));
    }

    @Test
    void patchIncident_returns200PatchProposed() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        CodeLocation loc = CodeLocation.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .targetRepo("repo")
                .filePath("FarmerSyncService.java")
                .startLine(30)
                .endLine(40)
                .confidence(ConfidenceLevel.HIGH)
                .snippet("void sync() { }")
                .build();
        when(codeLocatorService.locate(incident)).thenReturn(Optional.of(loc));

        PatchProposal proposal = PatchProposal.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .status(PatchStatus.VERIFIED)
                .unifiedDiff("--- a/file\n+++ b/file")
                .rationale("Fix connection leak")
                .verifierVerdict(VerifierVerdict.PASS)
                .build();
        when(fixAgent.proposeFix(incident, loc)).thenReturn(Optional.of(proposal));
        when(verifierAgent.verify(proposal, incident, loc)).thenReturn(true);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/patch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(SuccessCodes.PATCH_PROPOSED));
    }

    @Test
    void publishIncident_returns201PrOpened() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        PatchProposal proposal = PatchProposal.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .status(PatchStatus.VERIFIED)
                .unifiedDiff("diff")
                .build();
        when(patchProposalRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId))
                .thenReturn(Optional.of(proposal));

        PullRequest pr = PullRequest.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .patchProposal(proposal)
                .provider("GITHUB")
                .repoFullName("org/repo")
                .branchName("nightshift/fix-test")
                .baseBranch("main")
                .prNumber(101)
                .prUrl("https://github.com/org/repo/pull/101")
                .state(PrState.OPEN)
                .build();
        when(publisherService.publish(incident, proposal)).thenReturn(pr);

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/publish"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(SuccessCodes.PR_OPENED))
                .andExpect(jsonPath("$.data.pr_number").value(101));
    }

    @Test
    void muteIncident_returns200IncidentMuted() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(inv -> inv.getArgument(0));

        MuteIncidentRequest req = new MuteIncidentRequest("Known third-party issue");

        mockMvc.perform(post("/api/v1/incidents/" + incidentId + "/mute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(SuccessCodes.INCIDENT_MUTED))
                .andExpect(jsonPath("$.data.muted").value(true))
                .andExpect(jsonPath("$.data.mute_reason").value("Known third-party issue"));
    }

    @Test
    void getTrajectory_returns200WithSteps() throws Exception {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        AgentStep step = AgentStep.builder()
                .id(UUID.randomUUID())
                .incident(incident)
                .agentRole(AgentRole.TRIAGE)
                .stepIndex(1)
                .stepType(StepType.MODEL)
                .provider("heuristic")
                .model("rule-based")
                .status("OK")
                .inputSummary("Log input")
                .outputSummary("Triage verdict")
                .latencyMs(120)
                .build();
        when(agentStepRepository.findByIncidentIdOrderByStepIndexAsc(incidentId)).thenReturn(List.of(step));

        mockMvc.perform(get("/api/v1/incidents/" + incidentId + "/trajectory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].agent_role").value("TRIAGE"))
                .andExpect(jsonPath("$.data[0].latency_ms").value(120));
    }
}