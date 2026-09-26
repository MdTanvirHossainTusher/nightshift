package com.nightshift.controller;

import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.constant.code.SuccessCodes;
import com.nightshift.exception.BadResourceRequestException;
import com.nightshift.exception.ResourceNotFoundException;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;
import com.nightshift.payload.common.ApiResponse;
import com.nightshift.payload.common.PageResult;
import com.nightshift.payload.common.ResponseBuilder;
import com.nightshift.payload.request.MuteIncidentRequest;
import com.nightshift.payload.response.*;
import com.nightshift.repository.*;
import com.nightshift.service.agent.FixAgent;
import com.nightshift.service.agent.TriageAgent;
import com.nightshift.service.agent.VerifierAgent;
import com.nightshift.service.publish.PublisherService;
import com.nightshift.service.scan.CodeLocatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Incidents", description = "Endpoints for managing and remediating incidents")
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentRepository incidentRepository;
    private final CodeLocationRepository codeLocationRepository;
    private final PatchProposalRepository patchProposalRepository;
    private final PullRequestRepository pullRequestRepository;
    private final IncidentOccurrenceRepository incidentOccurrenceRepository;
    private final AgentStepRepository agentStepRepository;
    private final TriageAgent triageAgent;
    private final CodeLocatorService codeLocatorService;
    private final FixAgent fixAgent;
    private final VerifierAgent verifierAgent;
    private final PublisherService publisherService;

    @Operation(summary = "List incidents with pagination and filtering")
    @GetMapping
    public ResponseEntity<ApiResponse<List<IncidentResponse>>> listIncidents(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<Incident> incidentPage = incidentRepository.findByFilters(status, severity, PageRequest.of(page, size));
        PageResult<IncidentResponse> result = PageResult.of(incidentPage, IncidentResponse::from);
        return ResponseBuilder.ok(result);
    }

    @Operation(summary = "Get full incident detail including evidence, occurrences, and fixes")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<IncidentDetailResponse>> getIncident(@PathVariable UUID id) {
        Incident incident = findIncidentOrThrow(id);

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
        return ResponseBuilder.ok(detail);
    }

    @Operation(summary = "Re-run triage analysis on an incident")
    @PostMapping("/{id}/triage")
    public ResponseEntity<ApiResponse<IncidentResponse>> triageIncident(@PathVariable UUID id) {
        Incident incident = findIncidentOrThrow(id);
        triageAgent.triage(incident, null);
        Incident updated = findIncidentOrThrow(id);
        return ResponseBuilder.ok(
                IncidentResponse.from(updated),
                SuccessCodes.INCIDENT_TRIAGED,
                "Incident triaged successfully."
        );
    }

    @Operation(summary = "Locate code, propose fix, and run adversarial verification")
    @PostMapping("/{id}/patch")
    public ResponseEntity<ApiResponse<PatchProposalResponse>> patchIncident(@PathVariable UUID id) {
        Incident incident = findIncidentOrThrow(id);

        CodeLocation location = codeLocatorService.locate(incident)
                .orElseThrow(() -> new BadResourceRequestException(
                        ErrorCodes.SOURCE_FILE_NOT_LOCATED,
                        "Could not locate application source code for incident: " + incident.getFingerprint()
                ));

        PatchProposal proposal = fixAgent.proposeFix(incident, location)
                .orElseThrow(() -> new BadResourceRequestException(
                        ErrorCodes.PATCH_DOES_NOT_APPLY,
                        "Could not generate a valid patch within safety constraints."
                ));

        boolean verified = verifierAgent.verify(proposal, incident, location);

        return ResponseBuilder.ok(
                PatchProposalResponse.from(proposal),
                SuccessCodes.PATCH_PROPOSED,
                verified ? "Patch proposed and verified." : "Patch proposed but rejected by verifier."
        );
    }

    @Operation(summary = "Publish verified patch as a branch and pull request, then queue notifications")
    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<PullRequestResponse>> publishIncident(@PathVariable UUID id) {
        Incident incident = findIncidentOrThrow(id);

        PatchProposal proposal = patchProposalRepository.findTopByIncidentIdOrderByCreatedAtDesc(id)
                .orElseThrow(() -> new BadResourceRequestException(
                        ErrorCodes.EVIDENCE_REJECTED,
                        "No patch proposal exists for incident: " + id
                ));

        PullRequest pr = publisherService.publish(incident, proposal);

        return ResponseBuilder.created(
                PullRequestResponse.from(pr),
                SuccessCodes.PR_OPENED,
                "Pull request created and notification queued."
        );
    }

    @Operation(summary = "Mute an incident to stop reporting it in future scans")
    @PostMapping("/{id}/mute")
    public ResponseEntity<ApiResponse<IncidentResponse>> muteIncident(
            @PathVariable UUID id,
            @RequestBody(required = false) MuteIncidentRequest req
    ) {
        Incident incident = findIncidentOrThrow(id);
        incident.setMuted(true);
        if (req != null && req.reason() != null && !req.reason().isBlank()) {
            incident.setMuteReason(req.reason());
        }
        incident.setStatus(IncidentStatus.MUTED);
        incident = incidentRepository.save(incident);

        return ResponseBuilder.ok(
                IncidentResponse.from(incident),
                SuccessCodes.INCIDENT_MUTED,
                "Incident muted successfully."
        );
    }

    @Operation(summary = "Get audit trail trajectory (model and tool steps) for an incident")
    @GetMapping("/{id}/trajectory")
    public ResponseEntity<ApiResponse<List<AgentStepResponse>>> getTrajectory(@PathVariable UUID id) {
        findIncidentOrThrow(id);
        List<AgentStep> steps = agentStepRepository.findByIncidentIdOrderByStepIndexAsc(id);
        List<AgentStepResponse> responses = steps.stream().map(AgentStepResponse::from).toList();
        return ResponseBuilder.ok(responses);
    }

    private Incident findIncidentOrThrow(UUID id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCodes.NOT_FOUND,
                        "Incident not found: " + id
                ));
    }
}