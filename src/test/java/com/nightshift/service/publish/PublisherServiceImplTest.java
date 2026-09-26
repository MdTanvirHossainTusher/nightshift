package com.nightshift.service.publish;

import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.BadResourceRequestException;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.PrState;
import com.nightshift.model.enums.Severity;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.PatchProposalRepository;
import com.nightshift.repository.PullRequestRepository;
import com.nightshift.util.AgentStepRecorder;
import com.nightshift.util.PrBodyRenderer;
import com.nightshift.util.SecretMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
        PublisherServiceImpl.class,
        PrBodyRenderer.class,
        SecretMasker.class,
        AgentStepRecorder.class,
        NightshiftProperties.class
})
@TestPropertySource(properties = {
        "nightshift.publish.dry-run=true",
        "nightshift.git.repo=myorg/myrepo",
        "nightshift.git.base-branch=main"
})
class PublisherServiceImplTest {

    @Autowired
    private PublisherService publisherService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private PatchProposalRepository patchProposalRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PrBodyRenderer prBodyRenderer;

    private Incident incident;
    private PatchProposal proposal;

    @BeforeEach
    void setUp() {
        incident = incidentRepository.save(Incident.builder()
                .fingerprint("fp-pub-123456789")
                .title("Hikari connection pool leak")
                .serviceName("farmer-service")
                .loggerName("com.example.farmer.FarmerSyncService")
                .logLevel("ERROR")
                .exceptionType("java.sql.SQLTransientConnectionException")
                .normalizedMessage("Hikari connection pool exhaustion")
                .sampleMessage("Hikari connection pool exhaustion after 30000ms")
                .severity(Severity.CRITICAL)
                .severityRationale("Occurred 365 times with rising trend")
                .rootCause("Borrowed connection not in try-with-resources")
                .futureImpact("Monotonic exhaustion leads to complete platform outage")
                .recommendedAction("Wrap borrow in try-with-resources")
                .occurrenceCount(365)
                .firstSeenAt(Instant.now().minusSeconds(86400 * 7))
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.FIX_VERIFIED)
                .build());

        proposal = patchProposalRepository.save(PatchProposal.builder()
                .incident(incident)
                .status(PatchStatus.VERIFIED)
                .unifiedDiff("""
                        --- a/src/main/java/com/example/farmer/FarmerSyncService.java
                        +++ b/src/main/java/com/example/farmer/FarmerSyncService.java
                        @@ -36,1 +36,3 @@
                        - Connection connection = dataSource.getConnection();
                        + try (Connection connection = dataSource.getConnection()) {
                        """)
                .rationale("Enclose connection in try-with-resources")
                .testPlan("Run farmer sync test")
                .filesChanged(1)
                .linesAdded(3)
                .linesRemoved(1)
                .provider("heuristic")
                .model("rule-based")
                .build());
    }

    @Test
    void publish_dryRun_opensPrAndUpdatesEntities() {
        PullRequest pr = publisherService.publish(incident, proposal);

        assertThat(pr).isNotNull();
        assertThat(pr.getState()).isEqualTo(PrState.OPEN);
        assertThat(pr.getRepoFullName()).isEqualTo("myorg/myrepo");
        assertThat(pr.getBaseBranch()).isEqualTo("main");
        assertThat(pr.getBranchName()).isEqualTo("nightshift/fix-critical-fp-pub-1");
        assertThat(pr.getPrUrl()).contains("github.com/myorg/myrepo/pull/");

        PatchProposal updatedProposal = patchProposalRepository.findById(proposal.getId()).orElseThrow();
        assertThat(updatedProposal.getStatus()).isEqualTo(PatchStatus.PUBLISHED);

        Incident updatedIncident = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.PR_OPEN);
    }

    @Test
    void publish_duplicateBranch_throwsPrAlreadyOpen() {
        publisherService.publish(incident, proposal);

        assertThatThrownBy(() -> publisherService.publish(incident, proposal))
                .isInstanceOf(BadResourceRequestException.class)
                .satisfies(ex -> {
                    BadResourceRequestException bre = (BadResourceRequestException) ex;
                    assertThat(bre.getCode()).isEqualTo(ErrorCodes.PR_ALREADY_OPEN);
                });
    }

    @Test
    void prBodyRenderer_containsAllEightSections() {
        String body = prBodyRenderer.render(incident, proposal, null);

        assertThat(body)
                .contains("## 1. What is wrong")
                .contains("## 2. Severity")
                .contains("## 3. Why it happens")
                .contains("## 4. What it costs if we do not fix it now")
                .contains("## 5. Evidence")
                .contains("## 6. The fix")
                .contains("## 7. How to verify")
                .contains("## 8. Provenance")
                .contains("Monotonic exhaustion leads to complete platform outage")
                .contains("try (Connection connection = dataSource.getConnection())");
    }
}