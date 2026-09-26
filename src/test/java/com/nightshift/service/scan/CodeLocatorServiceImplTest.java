package com.nightshift.service.scan;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.repository.AgentStepRepository;
import com.nightshift.repository.CodeLocationRepository;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.LogSourceRepository;
import com.nightshift.util.AgentStepRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({
        CodeLocatorServiceImpl.class,
        AgentStepRecorder.class,
        NightshiftProperties.class
})
@TestPropertySource(properties = {
        "nightshift.locator.application-packages=com.example",
        "nightshift.workspace=./demo/target-repo"
})
class CodeLocatorServiceImplTest {

    @Autowired
    private CodeLocatorService codeLocatorService;

    @Autowired
    private CodeLocationRepository codeLocationRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private AgentStepRepository agentStepRepository;

    private final Path targetRepoPath = Path.of("demo/target-repo").toAbsolutePath();

    private Incident createIncident(String fingerprint, String service, String logger, String exception,
                                    String sampleMsg, String stacktrace) {
        Incident inc = Incident.builder()
                .fingerprint(fingerprint)
                .title("Test incident " + fingerprint)
                .serviceName(service)
                .loggerName(logger)
                .logLevel("ERROR")
                .exceptionType(exception)
                .normalizedMessage(sampleMsg)
                .sampleMessage(sampleMsg)
                .sampleStacktrace(stacktrace)
                .occurrenceCount(1)
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.NEW)
                .build();
        return incidentRepository.save(inc);
    }

    @Test
    void defect1_hikariLeakedConnection_resolvesFarmerSyncService() {
        String stack = """
                java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
                	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
                	at com.example.farmer.FarmerSyncService.pushPending(FarmerSyncService.java:36)
                	at com.example.farmer.FarmerSyncScheduler.run(FarmerSyncScheduler.java:58)
                """;

        Incident incident = createIncident("fp-def-1", "farmer-service",
                "com.example.farmer.FarmerSyncService", "java.sql.SQLTransientConnectionException",
                "Sync failed for batch: could not acquire a database connection", stack);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/farmer/FarmerSyncService.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(loc.getStartLine()).isLessThanOrEqualTo(36);
        assertThat(loc.getEndLine()).isGreaterThanOrEqualTo(36);
        assertThat(loc.getSnippet()).contains("NS_FRAME_POOL");
    }

    @Test
    void defect2_npeNameFormatter_resolvesNameFormatter() {
        String stack = """
                java.lang.NullPointerException: Cannot invoke "String.charAt(int)" because "middleName" is null
                	at com.example.common.NameFormatter.initials(NameFormatter.java:22)
                	at com.example.farmer.FarmerSyncService.cardLabel(FarmerSyncService.java:53)
                """;

        Incident incident = createIncident("fp-def-2", "farmer-service",
                "com.example.farmer.FarmerSyncService", "java.lang.NullPointerException",
                "Cannot invoke String.charAt because middleName is null", stack);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/common/NameFormatter.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(loc.getSnippet()).contains("NS_FRAME");
    }

    @Test
    void defect3_nPlusOneSlowQuery_resolvesViaLoggerAndMessage() {
        String msg = "Slow report build: region=east dealers=1271 queries=1217 duration=20692ms (InventoryReportService.java:30)";

        Incident incident = createIncident("fp-def-3", "sync-service",
                "com.example.sync.InventoryReportService", null,
                msg, null);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/sync/InventoryReportService.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(loc.getSnippet()).contains("NS_FRAME_NPLUSONE");
    }

    @Test
    void defect4_rateLimitedRetry_resolvesViaLoggerAndMessage() {
        String msg = "Settlement rate limited (HTTP 429, Retry-After=24s), retrying immediately [PaymentRetryClient.java:28]";

        Incident incident = createIncident("fp-def-4", "payment-service",
                "com.example.payment.PaymentRetryClient", null,
                msg, null);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/payment/PaymentRetryClient.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(loc.getSnippet()).contains("NS_FRAME_RETRY");
    }

    @Test
    void defect5_deserializationException_resolvesShipmentEventConsumer() {
        String stack = """
                com.example.sync.ShipmentEventConsumer$DeserializationException: Unexpected token START_ARRAY
                	at com.example.sync.ShipmentEventConsumer.onMessage(ShipmentEventConsumer.java:24)
                	at org.springframework.kafka.listener.KafkaMessageListenerContainer.doInvokeRecordListener(KafkaMessageListenerContainer.java:2815)
                """;

        Incident incident = createIncident("fp-def-5", "sync-service",
                "com.example.sync.ShipmentEventConsumer", "com.example.sync.ShipmentEventConsumer$DeserializationException",
                "Failed to deserialize shipment event", stack);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/sync/ShipmentEventConsumer.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(loc.getSnippet()).contains("NS_FRAME_DESER");
    }

    @Test
    void defect6_optimisticLockException_resolvesFarmerProfileService() {
        String stack = """
                com.example.farmer.FarmerProfileService$OptimisticLockException: Row was updated by another transaction
                	at com.example.farmer.FarmerProfileService.rename(FarmerProfileService.java:27)
                	at com.example.farmer.FarmerController.rename(FarmerController.java:96)
                """;

        Incident incident = createIncident("fp-def-6", "farmer-service",
                "com.example.farmer.FarmerProfileService", "com.example.farmer.FarmerProfileService$OptimisticLockException",
                "Concurrent update on farmer", stack);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isPresent();
        CodeLocation loc = locOpt.get();
        assertThat(loc.getFilePath()).isEqualTo("src/main/java/com/example/farmer/FarmerProfileService.java");
        assertThat(loc.getConfidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(loc.getSnippet()).contains("NS_FRAME_OPTLOCK");
    }

    @Test
    void defect7_frameworkLogger_producesNoCodeLocation() {
        Incident incident = createIncident("fp-def-7", "farmer-service",
                "org.springframework.boot.context.config.ConfigDataEnvironment", null,
                "Property 'spring.profiles' is deprecated", null);

        Optional<CodeLocation> locOpt = codeLocatorService.locate(incident, null, targetRepoPath);

        assertThat(locOpt).isEmpty();
    }
}
