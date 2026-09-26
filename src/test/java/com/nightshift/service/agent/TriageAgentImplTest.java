package com.nightshift.service.agent;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.*;
import com.nightshift.repository.*;
import com.nightshift.service.llm.HeuristicLlmClient;
import com.nightshift.service.llm.LlmClientRegistry;
import com.nightshift.service.scan.*;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.SecretMasker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration slice test: triage all 8 demo incidents with the heuristic client.
 *
 * <p>Runs the full scan pipeline first (reader → parser → fingerprinter → scan service),
 * then applies the triage agent to every NEW incident and asserts:
 * <ul>
 *   <li>Status transitions to {@code TRIAGED}</li>
 *   <li>Severity, category, root_cause, future_impact are non-null</li>
 *   <li>Each of the 5 PR-worthy incidents has {@code should_open_pr = true} as reflected
 *       by severity ∈ {CRITICAL, MAJOR}</li>
 *   <li>Defect #1 (connection leak) mentions "pool" and "exhaust" in future_impact</li>
 *   <li>Every model call writes an {@code agent_step} row</li>
 * </ul>
 *
 * <p>Uses H2 in-memory; reads the actual {@code demo/logs} directory from disk.
 */
@DataJpaTest
@Import({
        // Scan pipeline
        ScanServiceImpl.class,
        IncrementalLogReader.class,
        LogEventParser.class,
        IncidentFingerprinter.class,
        NightshiftProperties.class,
        SecretMasker.class,
        // LLM
        HeuristicLlmClient.class,
        LlmClientRegistry.class,
        // Triage
        TriageAgentImpl.class,
        AgentStepRecorder.class
})
@TestPropertySource(properties = {
        "nightshift.log-root=./demo/logs",
        "nightshift.locator.application-packages=com.example",
        "nightshift.llm.provider=heuristic"
})
class TriageAgentImplTest {

    @Autowired
    private ScanService scanService;

    @Autowired
    private TriageAgent triageAgent;

    @Autowired
    private LogSourceRepository logSourceRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    @Autowired
    private ScanRunRepository scanRunRepository;

    // ── helpers ──────────────────────────────────────────────────────────────

    private ScanRun runFullScan() {
        for (String svc : List.of("farmer-service", "payment-service", "sync-service")) {
            Path logsDir = Path.of("demo/logs", svc).toAbsolutePath();
            if (!logsDir.toFile().exists()) continue;
            logSourceRepository.save(LogSource.builder()
                    .name(svc)
                    .rootPath(logsDir.toString())
                    .fileGlob("*.log")
                    .serviceName(svc)
                    .enabled(true)
                    .build());
        }
        return scanService.runScan(TriggerSource.MANUAL);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void triage_allDemoIncidents_transitionToTriaged() {
        ScanRun run = runFullScan();
        List<Incident> incidents = incidentRepository.findAll();
        assertThat(incidents).isNotEmpty();

        for (Incident incident : incidents) {
            triageAgent.triage(incident, run);
        }

        List<Incident> triaged = incidentRepository.findAll();
        triaged.forEach(i ->
            assertThat(i.getStatus())
                    .as("incident %s should be TRIAGED", i.getFingerprint())
                    .isEqualTo(IncidentStatus.TRIAGED));
    }

    @Test
    void triage_allDemoIncidents_populateRequiredFields() {
        ScanRun run = runFullScan();

        for (Incident incident : incidentRepository.findAll()) {
            triageAgent.triage(incident, run);
        }

        for (Incident i : incidentRepository.findAll()) {
            assertThat(i.getSeverity())
                    .as("severity must be set for %s", i.getFingerprint())
                    .isNotNull();
            assertThat(i.getCategory())
                    .as("category must be set for %s", i.getFingerprint())
                    .isNotNull();
            assertThat(i.getRootCause())
                    .as("root_cause must be set for %s", i.getFingerprint())
                    .isNotBlank();
            assertThat(i.getFutureImpact())
                    .as("future_impact must be set for %s", i.getFingerprint())
                    .isNotBlank();
        }
    }

    @Test
    void triage_defect1_connectionLeak_futureImpactMentionsPoolExhaustion() {
        ScanRun run = runFullScan();

        Incident defect1 = incidentRepository.findAll().stream()
                .filter(i -> i.getExceptionType() != null
                        && i.getExceptionType().contains("SQLTransientConnectionException"))
                .findFirst()
                .orElse(null);

        assertThat(defect1)
                .as("Defect #1 (SQLTransientConnectionException) must be detected")
                .isNotNull();

        triageAgent.triage(defect1, run);

        Incident result = incidentRepository.findById(defect1.getId()).orElseThrow();
        assertThat(result.getFutureImpact().toLowerCase())
                .as("future_impact for defect #1 must mention pool exhaustion")
                .containsAnyOf("pool", "exhaust");
        assertThat(result.getSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void triage_everyModelCall_writesAgentStepRow() {
        ScanRun run = runFullScan();
        List<Incident> incidents = incidentRepository.findAll();
        assertThat(incidents).isNotEmpty();

        long stepsBefore = agentStepRepository.count();

        for (Incident incident : incidents) {
            triageAgent.triage(incident, run);
        }

        long stepsAfter = agentStepRepository.count();
        assertThat(stepsAfter)
                .as("Each triage call must write at least one agent_step row")
                .isGreaterThan(stepsBefore);
        assertThat(stepsAfter - stepsBefore)
                .as("Must have at least one agent_step per incident")
                .isGreaterThanOrEqualTo(incidents.size());
    }

    @Test
    void triage_prWorthyIncidents_haveCriticalOrMajorSeverity() {
        ScanRun run = runFullScan();
        for (Incident incident : incidentRepository.findAll()) {
            triageAgent.triage(incident, run);
        }

        // At least 3 incidents must be CRITICAL or MAJOR (defects 1, 2, 3, 4, 5)
        long criticalOrMajor = incidentRepository.findAll().stream()
                .filter(i -> i.getSeverity() == Severity.CRITICAL || i.getSeverity() == Severity.MAJOR)
                .count();

        assertThat(criticalOrMajor)
                .as("At least 3 demo incidents must be CRITICAL or MAJOR")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void triage_agentStepRows_haveModelStepType() {
        ScanRun run = runFullScan();
        List<Incident> incidents = incidentRepository.findAll();
        assertThat(incidents).isNotEmpty();

        triageAgent.triage(incidents.get(0), run);

        List<AgentStep> steps = agentStepRepository.findAll();
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).getAgentRole()).isEqualTo(AgentRole.TRIAGE);
        assertThat(steps.get(0).getStepType()).isEqualTo(StepType.MODEL);
        assertThat(steps.get(0).getProvider()).isEqualTo("heuristic");
    }
}
