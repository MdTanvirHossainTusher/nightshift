package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.repository.*;
import com.nightshift.util.SecretMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    @Transactional
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

            for (LogSource source : sources) {
                procesSource(source, run);
            }

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

                if (!LogEventParser.INCIDENT_LEVELS.contains(event.level())) {
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
}
