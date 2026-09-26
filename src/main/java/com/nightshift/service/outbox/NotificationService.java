package com.nightshift.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.Notification;
import com.nightshift.model.entity.OutboxEvent;
import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.enums.NotificationChannel;
import com.nightshift.model.enums.NotificationStatus;
import com.nightshift.model.enums.OutboxStatus;
import com.nightshift.repository.IncidentRepository;
import com.nightshift.repository.NotificationRepository;
import com.nightshift.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Creates and queues notifications for incidents and pull requests via the outbox pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AssignmentRulesService assignmentRulesService;
    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IncidentRepository incidentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Creates a notification record and an outbox event atomically for an opened PR.
     */
    @Transactional
    public OutboxEvent createNotificationAndOutbox(PullRequest pr) {
        Incident incident = pr.getIncident();
        Assignee assignee = assignmentRulesService.resolveAssignee(incident);

        // Update assignee handles on incident and PR if not already set
        if (incident.getAssigneeHandle() == null || incident.getAssigneeHandle().isBlank()) {
            incident.setAssigneeHandle(assignee.github());
            incident.setAssigneeEmail(assignee.email());
            incidentRepository.save(incident);
        }
        if (pr.getAssigneeHandle() == null || pr.getAssigneeHandle().isBlank()) {
            pr.setAssigneeHandle(assignee.github());
        }

        // Email subject: [Nightshift] <SEVERITY> · <title> · PR #<n>
        String sev = incident.getSeverity() != null ? incident.getSeverity().name() : "MAJOR";
        String prSuffix = pr.getPrNumber() != null ? " · PR #" + pr.getPrNumber() : "";
        String subject = String.format("[Nightshift] %s · %s%s", sev, incident.getTitle(), prSuffix);

        // Email body: summary plus PR link
        StringBuilder body = new StringBuilder();
        body.append(incident.getTitle()).append("\n\n");
        if (incident.getRootCause() != null && !incident.getRootCause().isBlank()) {
            body.append("Root Cause:\n").append(incident.getRootCause()).append("\n\n");
        }
        if (incident.getFutureImpact() != null && !incident.getFutureImpact().isBlank()) {
            body.append("Future Impact:\n").append(incident.getFutureImpact()).append("\n\n");
        }
        String prUrl = pr.getPrUrl() != null ? pr.getPrUrl() : "Branch: " + pr.getBranchName();
        body.append("Pull Request:\n").append(prUrl);

        Notification notification = Notification.builder()
                .incident(incident)
                .pullRequest(pr)
                .channel(NotificationChannel.EMAIL)
                .recipient(assignee.email())
                .subject(subject)
                .body(body.toString())
                .status(NotificationStatus.PENDING)
                .attempts(0)
                .build();
        notification = notificationRepository.save(notification);

        String payload;
        try {
            Map<String, Object> data = Map.of(
                    "notification_id", notification.getId().toString(),
                    "pull_request_id", pr.getId().toString(),
                    "incident_id", incident.getId().toString(),
                    "recipient", assignee.email(),
                    "subject", subject
            );
            payload = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            payload = String.format("{\"notification_id\":\"%s\",\"pull_request_id\":\"%s\",\"incident_id\":\"%s\"}",
                    notification.getId(), pr.getId(), incident.getId());
        }

        OutboxEvent event = OutboxEvent.builder()
                .type("PULL_REQUEST_OPENED")
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .build();
        event = outboxEventRepository.save(event);

        eventPublisher.publishEvent(new OutboxEventCreatedEvent(event.getId()));
        log.info("Created notification {} and outbox event {} for PR #{}", notification.getId(), event.getId(), pr.getPrNumber());
        return event;
    }
}