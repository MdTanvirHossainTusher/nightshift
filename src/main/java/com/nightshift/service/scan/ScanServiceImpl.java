package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.Severity;
import com.nightshift.repository.*;
import com.nightshift.service.agent.FixAgent;
import com.nightshift.service.agent.TriageAgent;
import com.nightshift.service.agent.VerifierAgent;
import com.nightshift.service.publish.PublisherService;
import com.nightshift.util.SecretMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link ScanService}.
 *
 * <p>For each enabled {@link LogSource}:
 * <ol>
 *   <li>Delegates to {@link IncrementalLogReader} to fetch only new events
 *   <li>Filters for WARN / ERROR / FATAL level events
 *   <li>Uses {@link IncidentFingerprinter} to produce a stable fingerprint
 *   <li>Upserts an {@link Incident} row (creates or bumps occurrence count / last-seen)
 *   <li>Appends an {@link IncidentOccurrence} row (up to the configured sample limit)
 *   <li>Updates {@link ScanRun} counters throughout
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanServiceImpl implements ScanService {

    private final LogSourceRepository logSourceRepository;
    private final ScanRunRepository scanRunRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentOccurrenceRepository incidentOccurrenceRepository;
    private final IncrementalLogReader logReader;
    private final IncidentFingerprinter fingerprinter;
    private final SecretMasker secretMasker;
    private final NightshiftProperties props;
    private final ObjectProvider<TriageAgent> triageAgentProvider;
    private final ObjectProvider<CodeLocatorService> codeLocatorServiceProvider;
    private final ObjectProvider<FixAgent> fixAgentProvider;
    private final ObjectProvider<VerifierAgent> verifierAgentProvider;
    private final ObjectProvider<PublisherService> publisherServiceProvider;
    private final ObjectProvider<com.nightshift.config.LogSourceInitializer> logSourceInitializerProvider;
    private final ObjectProvider<ScannedFileRepository> scannedFileRepositoryProvider;
    private final ObjectProvider<AgentStepRepository> agentStepRepositoryProvider;
    private final ObjectProvider<PullRequestRepository> pullRequestRepositoryProvider;
    private final ObjectProvider<PatchProposalRepository> patchProposalRepositoryProvider;
    private final ObjectProvider<CodeLocationRepository> codeLocationRepositoryProvider;
    private final ObjectProvider<NotificationRepository> notificationRepositoryProvider;

    @Override
    @Transactional
    public void resetCheckpoints(boolean resetData) {
        log.info("Resetting scan checkpoints (resetData={})", resetData);
        if (resetData) {
            notificationRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            agentStepRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            pullRequestRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            patchProposalRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            codeLocationRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            incidentOccurrenceRepository.deleteAllInBatch();
            incidentRepository.deleteAllInBatch();
            scannedFileRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
            scanRunRepository.deleteAllInBatch();
            logSourceInitializerProvider.ifAvailable(com.nightshift.config.LogSourceInitializer::initializeSourcesIfEmpty);
        } else {
            scannedFileRepositoryProvider.ifAvailable(org.springframework.data.jpa.repository.JpaRepository::deleteAllInBatch);
        }
        log.info("Reset checkpoints completed successfully.");
    }

    @Override
    @Transactional
    public ScanRun runScan(TriggerSource triggerSource, boolean resetCheckpoints) {
        if (resetCheckpoints) {
            resetCheckpoints(true);
        }
        return runScan(triggerSource);
    }

    @Override
    @Transactional(noRollbackFor = Exception.class)
    public ScanRun runScan(TriggerSource triggerSource) {
        log.info("Starting scan (trigger={})", triggerSource);

        if (scanRunRepository.existsByStatus(ScanStatus.RUNNING)) {
            throw new com.nightshift.exception.ResourceAlreadyExistsException(
                    com.nightshift.constant.code.ErrorCodes.SCAN_ALREADY_RUNNING,
                    "A scan is already in progress."
            );
        }

        ScanRun run;
        try {
            run = ScanRun.builder()
                    .triggerSource(triggerSource)
                    .status(ScanStatus.RUNNING)
                    .startedAt(Instant.now())
                    .build();
            run = scanRunRepository.saveAndFlush(run);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new com.nightshift.exception.ResourceAlreadyExistsException(
                    com.nightshift.constant.code.ErrorCodes.SCAN_ALREADY_RUNNING,
                    "A scan is already in progress (enforced by unique constraint)."
            );
        }

        try {
            List<LogSource> sources = logSourceRepository.findAll()
                    .stream()
                    .filter(LogSource::isEnabled)
                    .toList();

            if (sources.isEmpty()) {
                log.info("No active log sources found. Initializing default demo sources...");
                logSourceInitializerProvider.ifAvailable(com.nightshift.config.LogSourceInitializer::initializeSourcesIfEmpty);
                sources = logSourceRepository.findAll()
                        .stream()
                        .filter(LogSource::isEnabled)
                        .toList();
            }

            for (LogSource source : sources) {
                procesSource(source, run);
            }

            processIncidentsPipeline(run);

            run.setStatus(ScanStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Scan failed: {}", e.getMessage(), e);
            run.setStatus(ScanStatus.FAILED);
            run.setErrorMessage(e.getMessage());
        } finally {
            run.setFinishedAt(Instant.now());
            run = scanRunRepository.save(run);
        }

        log.info("Scan complete: status={} incidentsNew={} incidentsUpdated={}",
                run.getStatus(), run.getIncidentsNew(), run.getIncidentsUpdated());
        return run;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void procesSource(LogSource source, ScanRun run) {
        List<IncrementalLogReader.FileReadResult> results =
                logReader.readNewEvents(source, run);

        int maxSamples = 10; // default; Task 13 will wire this to props

        for (IncrementalLogReader.FileReadResult fileResult : results) {
            run.setFilesSeen(run.getFilesSeen() + 1);
            if (fileResult.events().isEmpty()) continue;

            run.setFilesWithNewData(run.getFilesWithNewData() + 1);
            long newBytes = fileResult.toOffset() - fileResult.fromOffset();
            run.setBytesRead(run.getBytesRead() + newBytes);

            for (LogEvent event : fileResult.events()) {
                run.setLinesParsed(run.getLinesParsed() + 1);

                if (!isIncidentCandidate(event)) {
                    continue;
                }

                run.setEventsMatched(run.getEventsMatched() + 1);

                String fp = fingerprinter.fingerprint(event);
                Optional<Incident> existing = incidentRepository.findByFingerprint(fp);

                if (existing.isPresent()) {
                    Incident incident = existing.get();
                    incident.setOccurrenceCount(incident.getOccurrenceCount() + 1);
                    incident.setLastSeenAt(event.timestamp() != null
                            ? event.timestamp() : Instant.now());
                    incidentRepository.save(incident);

                    // Only store up to maxSamples occurrences
                    long occCount = incidentOccurrenceRepository
                            .countByIncidentId(incident.getId());
                    if (occCount < maxSamples) {
                        saveOccurrence(incident, run, event, fileResult.absolutePath());
                    }

                    run.setIncidentsUpdated(run.getIncidentsUpdated() + 1);
                } else {
                    Incident incident = createIncident(fp, event, source);
                    incident = incidentRepository.save(incident);
                    saveOccurrence(incident, run, event, fileResult.absolutePath());
                    run.setIncidentsNew(run.getIncidentsNew() + 1);
                }
            }
        }
        scanRunRepository.save(run);
    }

    private Incident createIncident(String fingerprint, LogEvent event, LogSource source) {
        String normMsg = fingerprinter.normalise(event.message());
        String title = buildTitle(event);

        return Incident.builder()
                .fingerprint(fingerprint)
                .title(title)
                .serviceName(event.serviceName() != null
                        ? event.serviceName() : source.getServiceName())
                .loggerName(event.loggerName())
                .logLevel(event.level())
                .exceptionType(extractExceptionType(event.stacktrace()))
                .normalizedMessage(normMsg)
                .sampleMessage(event.message())
                .sampleStacktrace(event.stacktrace())
                .occurrenceCount(1)
                .firstSeenAt(event.timestamp() != null ? event.timestamp() : Instant.now())
                .lastSeenAt(event.timestamp() != null ? event.timestamp() : Instant.now())
                .status(IncidentStatus.NEW)
                .build();
    }

    private void saveOccurrence(Incident incident, ScanRun run, LogEvent event, String logFile) {
        IncidentOccurrence occ = IncidentOccurrence.builder()
                .incident(incident)
                .scanRun(run)
                .occurredAt(event.timestamp() != null ? event.timestamp() : Instant.now())
                .logFile(logFile)
                .lineNumber(event.lineNumber())
                .traceId(event.traceId())
                .threadName(event.threadName())
                .rawLine(secretMasker.mask(event.message()))
                .build();
        incidentOccurrenceRepository.save(occ);
    }

    private String buildTitle(LogEvent event) {
        String msg = event.message();
        // Use first ~80 chars of the message as title
        String base = msg != null && msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
        return base != null ? base : event.loggerName() + " " + event.level();
    }

    private String extractExceptionType(String stacktrace) {
        if (stacktrace == null || stacktrace.isBlank()) return null;
        String firstLine = stacktrace.lines().findFirst().orElse("").strip();
        int colon = firstLine.indexOf(':');
        String candidate = colon > 0 ? firstLine.substring(0, colon) : firstLine;
        return candidate.matches("[\\w.$]+") ? candidate : null;
    }

    private boolean isIncidentCandidate(LogEvent event) {
        if (!LogEventParser.INCIDENT_LEVELS.contains(event.level())) {
            return false;
        }
        if ("ERROR".equals(event.level()) || "FATAL".equals(event.level())) {
            return true;
        }
        if (event.stacktrace() != null && !event.stacktrace().isBlank()) {
            return true;
        }
        String msg = event.message();
        if (msg == null) return false;
        if (msg.contains("missing X-Request-Id header")
                || msg.contains("exceeds the 1000ms budget")
                || msg.contains("Offset commit failed on partition")) {
            return false;
        }
        return true;
    }

    private void processIncidentsPipeline(ScanRun run) {
        TriageAgent triageAgent = triageAgentProvider.getIfAvailable();
        CodeLocatorService codeLocatorService = codeLocatorServiceProvider.getIfAvailable();
        FixAgent fixAgent = fixAgentProvider.getIfAvailable();
        VerifierAgent verifierAgent = verifierAgentProvider.getIfAvailable();
        PublisherService publisherService = publisherServiceProvider.getIfAvailable();

        if (triageAgent == null) {
            return;
        }

        List<Incident> incidents = incidentRepository.findAll().stream()
                .filter(i -> i.getStatus() == IncidentStatus.NEW && !i.isMuted())
                .toList();

        for (Incident incident : incidents) {
            try {
                // Step 4: Triage
                triageAgent.triage(incident, run);
                incident = incidentRepository.findById(incident.getId()).orElse(incident);

                // Skip non-actionable or trivial incidents
                if (incident.getSeverity() == Severity.TRIVIAL
                        || (incident.getRecommendedAction() != null
                            && incident.getRecommendedAction().toLowerCase().contains("no code change required"))) {
                    log.info("Skipping fix proposal for non-actionable incident {}: {}", incident.getFingerprint(), incident.getTitle());
                    continue;
                }

                if (codeLocatorService == null || fixAgent == null || verifierAgent == null || publisherService == null) {
                    continue;
                }

                // Step 5: Code Locator
                Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, run);
                if (locOpt.isEmpty() || locOpt.get().getConfidence() == ConfidenceLevel.LOW) {
                    log.info("Code location missing or confidence LOW for incident {}", incident.getFingerprint());
                    continue;
                }
                CodeLocation location = locOpt.get();

                // Step 6: Fix Agent
                Optional<PatchProposal> propOpt = fixAgent.proposeFix(incident, location, run);
                if (propOpt.isEmpty()) {
                    log.info("No patch generated for incident {}", incident.getFingerprint());
                    continue;
                }
                PatchProposal proposal = propOpt.get();

                // Step 7: Verifier Agent
                boolean verified = verifierAgent.verify(proposal, incident, location, run);
                if (!verified || proposal.getStatus() != PatchStatus.VERIFIED) {
                    log.info("Patch failed verification for incident {}", incident.getFingerprint());
                    continue;
                }

                // Step 8: Publisher Service
                PullRequest pr = publisherService.publish(incident, proposal, run);
                if (pr != null) {
                    run.setPrsOpened(run.getPrsOpened() + 1);
                    scanRunRepository.save(run);
                }
            } catch (Exception e) {
                log.warn("Error running pipeline on incident {}: {}", incident.getFingerprint(), e.getMessage());
            }
        }
    }
}
