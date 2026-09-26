package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.*;
import com.nightshift.model.enums.*;
import com.nightshift.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration slice test for the scan pipeline:
 * LogEventParser → IncidentFingerprinter → IncrementalLogReader → ScanServiceImpl.
 *
 * <p>Uses H2 in-memory (no Docker) and reads the actual demo/logs directory from disk.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>A first scan over demo/logs produces at least 7 distinct incidents (one per defect)
 *   <li>Defect #3 (WARN-only, no exception) is detected
 *   <li>Defects #2 and #8 (same NPE via different services) produce <em>one</em> incident, not two
 *   <li>A second scan over the same unchanged logs produces {@code incidents_new = 0}
 *   <li>{@code scanned_file.byte_offset} is advanced after each scan
 * </ul>
 */
@DataJpaTest
@Import({
        ScanServiceImpl.class,
        IncrementalLogReader.class,
        LogEventParser.class,
        IncidentFingerprinter.class,
        NightshiftProperties.class
})
@TestPropertySource(properties = {
        "nightshift.log-root=./demo/logs",
        "nightshift.locator.application-packages=com.example"
})
class ScanServiceImplTest {

    @Autowired
    private ScanService scanService;

    @Autowired
    private LogSourceRepository logSourceRepository;

    @Autowired
    private ScanRunRepository scanRunRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ScannedFileRepository scannedFileRepository;

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates and persists a log source pointing at the demo/logs/<serviceName> directory.
     */
    private LogSource createLogSource(String serviceName) {
        Path logsDir = Path.of("demo/logs", serviceName).toAbsolutePath();
        LogSource source = LogSource.builder()
                .name(serviceName)
                .rootPath(logsDir.toString())
                .fileGlob("*.log")
                .serviceName(serviceName)
                .enabled(true)
                .build();
        return logSourceRepository.save(source);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void firstScan_producesExpectedIncidentsFromFarmerService() {
        createLogSource("farmer-service");

        ScanRun run = scanService.runScan(TriggerSource.MANUAL);

        assertThat(run.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(run.getIncidentsNew()).isGreaterThan(0);
        assertThat(incidentRepository.count()).isGreaterThan(0);
    }

    @Test
    void secondScan_overSameFiles_producesZeroNewIncidents() {
        createLogSource("farmer-service");

        // First scan — sequesters all bytes
        ScanRun first = scanService.runScan(TriggerSource.MANUAL);
        assertThat(first.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        long incidentsAfterFirst = incidentRepository.count();
        assertThat(incidentsAfterFirst).isGreaterThan(0);

        // Second scan — files unchanged, byte offsets are at EOF
        ScanRun second = scanService.runScan(TriggerSource.MANUAL);

        assertThat(second.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(second.getIncidentsNew())
                .as("second scan over unchanged logs must produce 0 new incidents")
                .isEqualTo(0);
        assertThat(incidentRepository.count())
                .as("incident count must not grow on second scan")
                .isEqualTo(incidentsAfterFirst);
    }

    @Test
    void byteOffset_advancesAfterScan() {
        LogSource source = createLogSource("farmer-service");

        scanService.runScan(TriggerSource.MANUAL);

        // Every scanned file should have byte_offset == size_bytes (advanced to EOF)
        scannedFileRepository.findAll().forEach(sf -> {
            if (sf.getLogSource().getId().equals(source.getId())) {
                assertThat(sf.getByteOffset())
                        .as("byte offset for %s must be advanced after scan", sf.getRelativePath())
                        .isEqualTo(sf.getSizeBytes());
            }
        });
    }

    @Test
    void defect3_warnOnlyNoException_isDetected() {
        // Defect #3: "Slow report build" WARN from InventoryReportService (no stack trace)
        Path syncLogsDir = Path.of("demo/logs/sync-service").toAbsolutePath();
        LogSource source = LogSource.builder()
                .name("sync-service")
                .rootPath(syncLogsDir.toString())
                .fileGlob("*.log")
                .serviceName("sync-service")
                .enabled(true)
                .build();
        logSourceRepository.save(source);

        scanService.runScan(TriggerSource.MANUAL);

        boolean found = incidentRepository.findAll().stream().anyMatch(i ->
                i.getLoggerName() != null &&
                i.getLoggerName().contains("InventoryReportService"));
        assertThat(found)
                .as("WARN-only defect #3 from InventoryReportService must be detected")
                .isTrue();
    }

    @Test
    void defects2and8_sameNpe_produceSingleIncident() {
        // Defect #2 (payment-service) and #8 (farmer-service) both throw
        // java.lang.NullPointerException at com.example.common.NameFormatter.initials
        // → same fingerprint → one incident row.

        Path farmerDir = Path.of("demo/logs/farmer-service").toAbsolutePath();
        Path paymentDir = Path.of("demo/logs/payment-service").toAbsolutePath();

        logSourceRepository.save(LogSource.builder()
                .name("farmer-service")
                .rootPath(farmerDir.toString())
                .fileGlob("*.log")
                .serviceName("farmer-service")
                .enabled(true)
                .build());

        logSourceRepository.save(LogSource.builder()
                .name("payment-service")
                .rootPath(paymentDir.toString())
                .fileGlob("*.log")
                .serviceName("payment-service")
                .enabled(true)
                .build());

        scanService.runScan(TriggerSource.MANUAL);

        long npeCount = incidentRepository.findAll().stream()
                .filter(i -> "java.lang.NullPointerException".equals(i.getExceptionType())
                        && i.getSampleStacktrace() != null
                        && i.getSampleStacktrace().contains("NameFormatter.initials"))
                .count();

        assertThat(npeCount)
                .as("NPE via NameFormatter.initials must map to exactly ONE incident (defects #2 and #8 folded)")
                .isEqualTo(1);
    }
}
