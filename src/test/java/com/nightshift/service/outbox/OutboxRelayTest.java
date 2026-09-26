package com.nightshift.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.Notification;
import com.nightshift.model.entity.OutboxEvent;
import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.NotificationStatus;
import com.nightshift.model.enums.OutboxStatus;
import com.nightshift.model.enums.PrState;
import com.nightshift.model.enums.Severity;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.NotificationRepository;
import com.nightshift.repository.OutboxEventRepository;
import com.nightshift.repository.PullRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({
        OutboxRelay.class,
        NotificationService.class,
        AssignmentRulesService.class,
        EmailSender.class,
        NightshiftProperties.class,
        ObjectMapper.class
})
class OutboxRelayTest {

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @MockitoBean
    private JavaMailSender javaMailSender;

    private Incident incident;
    private PullRequest pullRequest;

    @BeforeEach
    void setUp() {
        incident = incidentRepository.save(Incident.builder()
                .fingerprint("fp-outbox-test-01")
                .title("Hikari connection pool leak")
                .serviceName("farmer-service")
                .loggerName("com.example.farmer.FarmerSyncService")
                .logLevel("ERROR")
                .normalizedMessage("Hikari connection pool exhausted")
                .firstSeenAt(Instant.now())
                .lastSeenAt(Instant.now())
                .status(IncidentStatus.PR_OPEN)
                .severity(Severity.CRITICAL)
                .build());

        pullRequest = pullRequestRepository.save(PullRequest.builder()
                .incident(incident)
                .provider("GITHUB")
                .repoFullName("org/repo")
                .branchName("nightshift/fix-critical-fp-outbox")
                .baseBranch("main")
                .prNumber(42)
                .prUrl("https://github.com/org/repo/pull/42")
                .state(PrState.OPEN)
                .build());
    }

    @Test
    void notificationService_createsAtomicallyAndPublishes() {
        OutboxEvent event = notificationService.createNotificationAndOutbox(pullRequest);

        assertThat(event).isNotNull();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getType()).isEqualTo("PULL_REQUEST_OPENED");

        var notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notif = notifications.get(0);
        assertThat(notif.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notif.getRecipient()).isEqualTo("dev.a@example.com");
        assertThat(notif.getSubject()).contains("PR #42");
    }

    @Test
    void outboxRelay_processesPendingEvent_dispatchesEmailAndMarksSent() {
        OutboxEvent event = notificationService.createNotificationAndOutbox(pullRequest);

        outboxRelay.processEvent(event);

        verify(javaMailSender, times(1)).send(any(SimpleMailMessage.class));

        OutboxEvent updatedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(updatedEvent.getProcessedAt()).isNotNull();

        var notifications = notificationRepository.findAll();
        assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notifications.get(0).getSentAt()).isNotNull();
    }

    @Test
    void outboxRelay_handlesFailure_retriesAndMarksFailedOnMaxAttempts() {
        doThrow(new RuntimeException("Mail server down"))
                .when(javaMailSender).send(any(SimpleMailMessage.class));

        OutboxEvent event = notificationService.createNotificationAndOutbox(pullRequest);

        // Run 4 failed attempts
        for (int i = 0; i < 4; i++) {
            outboxRelay.processEvent(event);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isEqualTo(i + 1);
            assertThat(event.getLastError()).contains("Mail server down");
        }

        // 5th attempt: should mark FAILED
        outboxRelay.processEvent(event);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(5);

        var notifications = notificationRepository.findAll();
        assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }
}