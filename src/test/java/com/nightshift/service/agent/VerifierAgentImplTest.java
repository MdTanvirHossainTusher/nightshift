package com.nightshift.service.agent;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.VerifierVerdict;
import com.nightshift.repository.CodeLocationRepository;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.service.llm.HeuristicLlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.util.AgentStepRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({
        VerifierAgentImpl.class,
        AgentStepRecorder.class,
        LlmClientRegistry.class,
        HeuristicLlmClient.class,
        NightshiftProperties.class
})
@TestPropertySource(properties = {
        "nightshift.llm.provider=heuristic"
})
class VerifierAgentImplTest {

    @Autowired
    private VerifierAgent verifierAgent;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private CodeLocationRepository codeLocationRepository;

    @Autowired
    private PatchProposalRepository patchProposalRepository;

    private Incident incident;
    private CodeLocation codeLocation;

    @BeforeEach
    void setUp() {
        incident = incidentRepository.save(Incident.builder()
                .fingerprint("fp-verify-1")
                .title("Hikari connection leak")
                .serviceName("farmer-service")
                .loggerName("com.example.farmer.FarmerSyncService")
                .logLevel("ERROR")
                .exceptionType("java.sql.SQLTransientConnectionException")
                .normalizedMessage("Hikari connection pool leak")
                .rootCause("Connection borrowed outside try-with-resources")
                .recommendedAction("Wrap borrow in try-with-resources")
                .occurrenceCount(10)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.FIX_PROPOSED)
                .build());

        codeLocation = codeLocationRepository.save(CodeLocation.builder()
                .incident(incident)
                .targetRepo("demo/target-repo")
                .filePath("src/main/java/com/example/farmer/FarmerSyncService.java")
                .startLine(1)
                .endLine(60)
                .confidence(ConfidenceLevel.HIGH)
                .snippet("Connection connection = dataSource.getConnection(); // NS_FRAME_POOL")
                .build());
    }

    @Test
    void validPatch_passesVerification() {
        PatchProposal proposal = patchProposalRepository.save(PatchProposal.builder()
                .incident(incident)
                .status(PatchStatus.DRAFT)
                .unifiedDiff("""
                        --- a/src/main/java/com/example/farmer/FarmerSyncService.java
                        +++ b/src/main/java/com/example/farmer/FarmerSyncService.java
                        @@ -36,1 +36,3 @@
                        - Connection connection = dataSource.getConnection();
                        + try (Connection connection = dataSource.getConnection()) {
                        """)
                .rationale("Encloses connection in try-with-resources")
                .testPlan("Run farmer sync test")
                .filesChanged(1)
                .linesAdded(3)
                .linesRemoved(1)
                .build());

        boolean passed = verifierAgent.verify(proposal, incident, codeLocation);

        assertThat(passed).isTrue();
        PatchProposal updatedProposal = patchProposalRepository.findById(proposal.getId()).orElseThrow();
        assertThat(updatedProposal.getStatus()).isEqualTo(PatchStatus.VERIFIED);
        assertThat(updatedProposal.getVerifierVerdict()).isEqualTo(VerifierVerdict.PASS);
        assertThat(updatedProposal.getVerifierNotes()).contains("verified");

        Incident updatedIncident = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.FIX_VERIFIED);
    }

    @Test
    void unsupportedPatch_failsVerification() {
        PatchProposal proposal = patchProposalRepository.save(PatchProposal.builder()
                .incident(incident)
                .status(PatchStatus.DRAFT)
                .unifiedDiff("""
                        --- a/src/main/java/com/example/farmer/FarmerSyncService.java
                        +++ b/src/main/java/com/example/farmer/FarmerSyncService.java
                        @@ -36,1 +36,1 @@
                        - reject_me unsupported contradict hallucinated
                        """)
                .rationale("Hallucinated refactoring")
                .testPlan("None")
                .filesChanged(1)
                .linesAdded(1)
                .linesRemoved(1)
                .build());

        boolean passed = verifierAgent.verify(proposal, incident, codeLocation);

        assertThat(passed).isFalse();
        PatchProposal updatedProposal = patchProposalRepository.findById(proposal.getId()).orElseThrow();
        assertThat(updatedProposal.getStatus()).isEqualTo(PatchStatus.REJECTED);
        assertThat(updatedProposal.getVerifierVerdict()).isEqualTo(VerifierVerdict.FAIL);
        assertThat(updatedProposal.getRejectionCode()).isEqualTo(ErrorCodes.EVIDENCE_REJECTED);

        Incident updatedIncident = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.TRIAGED_PATCH_REJECTED);
    }
}