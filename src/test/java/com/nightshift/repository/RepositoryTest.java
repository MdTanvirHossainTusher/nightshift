package com.nightshift.repository;

import com.nightshift.model.entity.*;
import com.nightshift.model.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests — no full Spring context, H2 in-memory, Flyway disabled.
 * Each test verifies persistence round-trip and any custom query methods.
 */
@DataJpaTest
class RepositoryTest {

    @Autowired
    private LogSourceRepository logSourceRepository;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @Autowired
    private ScannedFileRepository scannedFileRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentOccurrenceRepository incidentOccurrenceRepository;

    @Autowired
    private CodeLocationRepository codeLocationRepository;

    @Autowired
    private PatchProposalRepository patchProposalRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    // ── LogSource ─────────────────────────────────────────────────────────────

    @Test
    void logSource_saveAndFindById() {
        LogSource source = LogSource.builder()
                .name("payments")
                .rootPath("/var/log/payments")
                .build();

        LogSource saved = logSourceRepository.save(source);

        assertThat(saved.getId()).isNotNull();
        assertThat(logSourceRepository.findById(saved.getId())).isPresent();
    }

    // ── ScanRun ───────────────────────────────────────────────────────────────

    @Test
    void scanRun_saveAndFindById() {
        ScanRun run = ScanRun.builder()
                .triggerSource(TriggerSource.MANUAL)
                .status(ScanStatus.RUNNING)
                .startedAt(Instant.now())
                .build();

        ScanRun saved = scanRunRepository.save(run);

        assertThat(saved.getId()).isNotNull();
        assertThat(scanRunRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void scanRunRepository_existsByStatus_returnsTrueWhenRunning() {
        ScanRun run = ScanRun.builder()
                .triggerSource(TriggerSource.SCHEDULE)
                .status(ScanStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        scanRunRepository.save(run);

        assertThat(scanRunRepository.existsByStatus(ScanStatus.RUNNING)).isTrue();
        assertThat(scanRunRepository.existsByStatus(ScanStatus.COMPLETED)).isFalse();
    }

    @Test
    void scanRunRepository_existsByStatus_returnsFalseWhenNoneRunning() {
        assertThat(scanRunRepository.existsByStatus(ScanStatus.RUNNING)).isFalse();
    }

    // ── ScannedFile ───────────────────────────────────────────────────────────

    @Test
    void scannedFileRepository_findByLogSourceIdAndRelativePath_returnsCheckpoint() {
        LogSource source = logSourceRepository.save(LogSource.builder()
                .name("auth-service")
                .rootPath("/var/log/auth")
                .build());

        ScannedFile file = ScannedFile.builder()
                .logSource(source)
                .relativePath("app.log")
                .sizeBytes(1024L)
                .byteOffset(512L)
                .contentHash("abc123")
                .lastModifiedAt(Instant.now())
                .lastScannedAt(Instant.now())
                .build();
        scannedFileRepository.save(file);

        Optional<ScannedFile> found = scannedFileRepository
                .findByLogSourceIdAndRelativePath(source.getId(), "app.log");

        assertThat(found).isPresent();
        assertThat(found.get().getByteOffset()).isEqualTo(512L);
        assertThat(found.get().getContentHash()).isEqualTo("abc123");
    }

    @Test
    void scannedFileRepository_findByLogSourceIdAndRelativePath_returnsEmptyForUnknownPath() {
        LogSource source = logSourceRepository.save(LogSource.builder()
                .name("billing")
                .rootPath("/var/log/billing")
                .build());

        Optional<ScannedFile> found = scannedFileRepository
                .findByLogSourceIdAndRelativePath(source.getId(), "nonexistent.log");

        assertThat(found).isEmpty();
    }

    // ── Incident ──────────────────────────────────────────────────────────────

    @Test
    void incidentRepository_saveAndFindByFingerprint() {
        Incident incident = buildIncident("fp-001");
        incidentRepository.save(incident);

        Optional<Incident> found = incidentRepository.findByFingerprint("fp-001");

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test incident");
    }

    @Test
    void incidentRepository_findByFingerprint_returnsEmptyForUnknown() {
        assertThat(incidentRepository.findByFingerprint("no-such-fp")).isEmpty();
    }

    // ── IncidentOccurrence ────────────────────────────────────────────────────

    @Test
    void incidentOccurrence_saveAndFindById() {
        Incident incident = incidentRepository.save(buildIncident("fp-occ-001"));

        IncidentOccurrence occ = IncidentOccurrence.builder()
                .incident(incident)
                .occurredAt(Instant.now())
                .logFile("/var/log/app.log")
                .lineNumber(42)
                .rawLine("ERROR Something went wrong")
                .build();
        IncidentOccurrence saved = incidentOccurrenceRepository.save(occ);

        assertThat(saved.getId()).isNotNull();
        assertThat(incidentOccurrenceRepository.findById(saved.getId())).isPresent();
    }

    // ── CodeLocation ──────────────────────────────────────────────────────────

    @Test
    void codeLocation_saveAndFindById() {
        Incident incident = incidentRepository.save(buildIncident("fp-loc-001"));

        CodeLocation loc = CodeLocation.builder()
                .incident(incident)
                .targetRepo("org/payments")
                .filePath("src/main/java/PaymentService.java")
                .startLine(100)
                .endLine(110)
                .confidence(ConfidenceLevel.HIGH)
                .build();
        CodeLocation saved = codeLocationRepository.save(loc);

        assertThat(saved.getId()).isNotNull();
        assertThat(codeLocationRepository.findById(saved.getId())).isPresent();
    }

    // ── PatchProposal ─────────────────────────────────────────────────────────

    @Test
    void patchProposal_saveAndFindById() {
        Incident incident = incidentRepository.save(buildIncident("fp-patch-001"));

        PatchProposal patch = PatchProposal.builder()
                .incident(incident)
                .status(PatchStatus.DRAFT)
                .unifiedDiff("--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n")
                .build();
        PatchProposal saved = patchProposalRepository.save(patch);

        assertThat(saved.getId()).isNotNull();
        assertThat(patchProposalRepository.findById(saved.getId())).isPresent();
    }

    // ── PullRequest ───────────────────────────────────────────────────────────

    @Test
    void pullRequest_saveAndFindById() {
        Incident incident = incidentRepository.save(buildIncident("fp-pr-001"));

        PullRequest pr = PullRequest.builder()
                .incident(incident)
                .repoFullName("org/payments")
                .branchName("nightshift/fix-fp-pr-001")
                .state(PrState.OPEN)
                .build();
        PullRequest saved = pullRequestRepository.save(pr);

        assertThat(saved.getId()).isNotNull();
        assertThat(pullRequestRepository.findById(saved.getId())).isPresent();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    @Test
    void notification_saveAndFindById() {
        Notification notification = Notification.builder()
                .channel(NotificationChannel.EMAIL)
                .recipient("dev@example.com")
                .subject("New incident PR")
                .status(NotificationStatus.PENDING)
                .build();
        Notification saved = notificationRepository.save(notification);

        assertThat(saved.getId()).isNotNull();
        assertThat(notificationRepository.findById(saved.getId())).isPresent();
    }

    // ── AgentStep ─────────────────────────────────────────────────────────────

    @Test
    void agentStep_saveAndFindById() {
        AgentStep step = AgentStep.builder()
                .agentRole(AgentRole.TRIAGE)
                .stepIndex(0)
                .stepType(StepType.MODEL)
                .status("OK")
                .build();
        AgentStep saved = agentStepRepository.save(step);

        assertThat(saved.getId()).isNotNull();
        assertThat(agentStepRepository.findById(saved.getId())).isPresent();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Incident buildIncident(String fingerprint) {
        return Incident.builder()
                .fingerprint(fingerprint)
                .title("Test incident")
                .logLevel("ERROR")
                .normalizedMessage("Something went wrong")
                .status(IncidentStatus.NEW)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .build();
    }
}
