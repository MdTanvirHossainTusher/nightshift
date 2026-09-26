package com.nightshift.service.agent;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.PatchRejectedException;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.repository.AgentStepRepository;
import com.nightshift.repository.CodeLocationRepository;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.service.llm.HeuristicLlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.PatchGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
        FixAgentImpl.class,
        PatchGuard.class,
        AgentStepRecorder.class,
        LlmClientRegistry.class,
        HeuristicLlmClient.class,
        NightshiftProperties.class
})
@TestPropertySource(properties = {
        "nightshift.llm.provider=heuristic",
        "nightshift.workspace=./demo/target-repo",
        "nightshift.locator.application-packages=com.example"
})
class FixAgentImplTest {

    @Autowired
    private FixAgent fixAgent;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private CodeLocationRepository codeLocationRepository;

    @Autowired
    private PatchProposalRepository patchProposalRepository;

    private final Path demoRepo = Path.of("demo/target-repo").toAbsolutePath();

    private Incident createIncident(String fingerprint, String service, String logger, String exception,
                                    String sampleMsg, String rootCause, String action) {
        Incident inc = Incident.builder()
                .fingerprint(fingerprint)
                .title("Incident " + fingerprint)
                .serviceName(service)
                .loggerName(logger)
                .logLevel("ERROR")
                .exceptionType(exception)
                .normalizedMessage(sampleMsg)
                .sampleMessage(sampleMsg)
                .rootCause(rootCause)
                .recommendedAction(action)
                .occurrenceCount(1)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.TRIAGED)
                .build();
        return incidentRepository.save(inc);
    }

    private CodeLocation createLocation(Incident incident, String file, int start, int end,
                                        ConfidenceLevel conf, String snippet) {
        CodeLocation loc = CodeLocation.builder()
                .incident(incident)
                .targetRepo(demoRepo.toString())
                .filePath(file)
                .startLine(start)
                .endLine(end)
                .confidence(conf)
                .snippet(snippet)
                .build();
        return codeLocationRepository.save(loc);
    }

    @Test
    void defect1_proposesValidDiff_passesGuards() {
        Incident incident = createIncident("fp-fix-1", "farmer-service",
                "com.example.farmer.FarmerSyncService", "java.sql.SQLTransientConnectionException",
                "Sync failed: could not acquire connection",
                "Connection leaked on exception path",
                "Wrap borrow in try-with-resources");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/farmer/FarmerSyncService.java",
                1, 60, ConfidenceLevel.HIGH, "dataSource.getConnection(); // NS_FRAME_POOL");

        Optional<PatchProposal> proposalOpt = fixAgent.proposeFix(incident, loc, null, demoRepo);

        assertThat(proposalOpt).isPresent();
        PatchProposal proposal = proposalOpt.get();
        assertThat(proposal.getStatus()).isEqualTo(PatchStatus.DRAFT);
        assertThat(proposal.getUnifiedDiff()).contains("try (Connection connection");
        assertThat(proposal.getFilesChanged()).isEqualTo(1);
        assertThat(proposal.getLinesAdded()).isGreaterThan(0);
        assertThat(proposal.getLinesRemoved()).isGreaterThan(0);
    }

    @Test
    void defect2_proposesValidDiff_passesGuards() {
        Incident incident = createIncident("fp-fix-2", "farmer-service",
                "com.example.common.NameFormatter", "java.lang.NullPointerException",
                "Cannot invoke charAt because middleName is null",
                "middleName is null",
                "Treat middleName as optional");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/common/NameFormatter.java",
                1, 30, ConfidenceLevel.HIGH, "middleName.charAt(0); // NS_FRAME");

        Optional<PatchProposal> proposalOpt = fixAgent.proposeFix(incident, loc, null, demoRepo);

        assertThat(proposalOpt).isPresent();
        PatchProposal proposal = proposalOpt.get();
        assertThat(proposal.getStatus()).isEqualTo(PatchStatus.DRAFT);
        assertThat(proposal.getUnifiedDiff()).contains("middleName != null");
    }

    @Test
    void defect3_proposesValidDiff_passesGuards() {
        Incident incident = createIncident("fp-fix-3", "sync-service",
                "com.example.sync.InventoryReportService", null,
                "Slow report build: region=east dealers=1271 queries=1217",
                "N+1 query in loop",
                "Batch query via findByDealerIdIn");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/sync/InventoryReportService.java",
                1, 50, ConfidenceLevel.MEDIUM, "items.findByDealerId // NS_FRAME_NPLUSONE");

        Optional<PatchProposal> proposalOpt = fixAgent.proposeFix(incident, loc, null, demoRepo);

        assertThat(proposalOpt).isPresent();
        PatchProposal proposal = proposalOpt.get();
        assertThat(proposal.getStatus()).isEqualTo(PatchStatus.DRAFT);
        assertThat(proposal.getUnifiedDiff()).contains("findByDealerIdIn");
    }

    @Test
    void defect4_proposesValidDiff_passesGuards() {
        Incident incident = createIncident("fp-fix-4", "payment-service",
                "com.example.payment.PaymentRetryClient", null,
                "Settlement rate limited (HTTP 429, Retry-After=24s)",
                "Unbounded tight retry loop",
                "Bound attempts and add backoff");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/payment/PaymentRetryClient.java",
                1, 50, ConfidenceLevel.MEDIUM, "continue; // NS_FRAME_RETRY");

        Optional<PatchProposal> proposalOpt = fixAgent.proposeFix(incident, loc, null, demoRepo);

        assertThat(proposalOpt).isPresent();
        PatchProposal proposal = proposalOpt.get();
        assertThat(proposal.getStatus()).isEqualTo(PatchStatus.DRAFT);
        assertThat(proposal.getUnifiedDiff()).contains("MAX_ATTEMPTS");
    }

    @Test
    void defect5_proposesValidDiff_passesGuards() {
        Incident incident = createIncident("fp-fix-5", "sync-service",
                "com.example.sync.ShipmentEventConsumer", "com.example.sync.ShipmentEventConsumer$DeserializationException",
                "Failed to deserialize shipment event",
                "Swallowed DeserializationException",
                "Route to DLQ");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/sync/ShipmentEventConsumer.java",
                1, 48, ConfidenceLevel.HIGH, "deserializer.read(payload); // NS_FRAME_DESER");

        Optional<PatchProposal> proposalOpt = fixAgent.proposeFix(incident, loc, null, demoRepo);

        assertThat(proposalOpt).isPresent();
        PatchProposal proposal = proposalOpt.get();
        assertThat(proposal.getStatus()).isEqualTo(PatchStatus.DRAFT);
        assertThat(proposal.getUnifiedDiff()).contains("deadLetter");
    }

    @Test
    void lowConfidenceLocation_throwsSourceFileNotLocated() {
        Incident incident = createIncident("fp-fix-low", "farmer-service",
                "com.example.farmer.SomeClass", null, "Some warning", "Cause", "Action");

        CodeLocation loc = createLocation(incident, "src/main/java/com/example/farmer/SomeClass.java",
                1, 10, ConfidenceLevel.LOW, "class SomeClass {}");

        assertThatThrownBy(() -> fixAgent.proposeFix(incident, loc, null, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.SOURCE_FILE_NOT_LOCATED);
                });
    }

    @Test
    void nullCodeLocation_throwsSourceFileNotLocated() {
        Incident incident = createIncident("fp-fix-null", "farmer-service",
                "org.springframework.boot.context.config.ConfigDataEnvironment", null,
                "deprecated config", "Framework deprecation", "Ignore");

        assertThatThrownBy(() -> fixAgent.proposeFix(incident, null, null, demoRepo))
                .isInstanceOf(PatchRejectedException.class)
                .satisfies(ex -> {
                    PatchRejectedException pre = (PatchRejectedException) ex;
                    assertThat(pre.getCode()).isEqualTo(ErrorCodes.SOURCE_FILE_NOT_LOCATED);
                });
    }
}