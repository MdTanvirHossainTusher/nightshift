package com.nightshift.service.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nightshift.config.properties.NightshiftProperties;
import com.nightshift.model.entity.Notification;
import com.nightshift.model.entity.OutboxEvent;
import com.nightshift.model.enums.NotificationStatus;
import com.nightshift.model.enums.OutboxStatus;
import com.nightshift.repository.NotificationRepository;
import com.nightshift.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox relay that publishes queued outbox events.
 * Listens after transaction commit for immediate dispatch and sweeps
 * periodically to retry or catch any missed events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationRepository notificationRepository;
    private final EmailSender emailSender;
    private final NightshiftProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;

    /**
     * Immediate path: triggers after the transaction that saved the OutboxEvent has committed.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxEventCreated(OutboxEventCreatedEvent event) {
        log.debug("OutboxRelay received immediate event after commit: {}", event.eventId());
        try {
            processEventById(event.eventId());
        } catch (Exception e) {
            log.warn("Immediate outbox dispatch failed for {}: {}. Will be retried by sweeper.",
                    event.eventId(), e.getMessage());
        }
    }

    /**
     * Periodic sweeper: picks up un-dispatched or failed pending events.
     */
    @Scheduled(fixedDelayString = "${nightshift.outbox.sweep-interval-ms:30000}")
    public void sweepPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(
                OutboxStatus.PENDING, MAX_ATTEMPTS, PageRequest.of(0, 20)
        );

        if (!pending.isEmpty()) {
            log.debug("Outbox sweeper found {} pending events to process", pending.size());
            for (OutboxEvent event : pending) {
                try {
                    processEvent(event);
                } catch (Exception e) {
                    log.error("Error processing outbox event {}: {}", event.getId(), e.getMessage());
                }
            }
        }
    }

    @Transactional
    public void processEventById(UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(this::processEvent);
    }

    @Transactional
    public void processEvent(OutboxEvent event) {
        if (event.getStatus() != OutboxStatus.PENDING) {
            return;
        }

        event.setAttempts(event.getAttempts() + 1);

        try {
            Notification notification = resolveNotification(event);
            String transport = properties.getMessaging() != null ? properties.getMessaging().getTransport() : "local";
            if ("kafka".equalsIgnoreCase(transport)) {
                dispatchKafka(event);
            } else {
                dispatchLocal(notification);
            }

            event.setStatus(OutboxStatus.SENT);
            event.setProcessedAt(Instant.now());
            event.setLastError(null);

            if (notification != null) {
                notification.setStatus(NotificationStatus.SENT);
                notification.setSentAt(Instant.now());
                notification.setAttempts(event.getAttempts());
                notification.setLastError(null);
                notificationRepository.save(notification);
            }

            outboxEventRepository.save(event);
            log.info("Outbox event {} processed successfully via transport '{}'", event.getId(), transport);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Failed attempt {} for outbox event {}: {}", event.getAttempts(), event.getId(), errMsg);
            event.setLastError(errMsg);

            Notification notification = null;
            try {
                notification = resolveNotification(event);
            } catch (Exception ignored) {}

            if (notification != null) {
                notification.setLastError(errMsg);
                notification.setAttempts(event.getAttempts());
            }

            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxStatus.FAILED);
                if (notification != null) {
                    notification.setStatus(NotificationStatus.FAILED);
                }
                log.error("Outbox event {} marked FAILED after reaching maximum attempts ({})", event.getId(), MAX_ATTEMPTS);
            }

            if (notification != null) {
                notificationRepository.save(notification);
            }
            outboxEventRepository.save(event);
        }
    }

    private void dispatchLocal(Notification notification) {
        if (notification == null) {
            throw new IllegalStateException("Notification entity could not be resolved from outbox event");
        }
        emailSender.send(notification);
    }

    private void dispatchKafka(OutboxEvent event) {
        KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        if (kafkaTemplate == null) {
            throw new IllegalStateException("Kafka transport configured but KafkaTemplate bean is unavailable");
        }
        kafkaTemplate.send("nightshift.notifications", event.getType(), event.getPayload());
        log.info("Sent outbox event {} to Kafka topic 'nightshift.notifications'", event.getId());
    }

    private Notification resolveNotification(OutboxEvent event) {
        try {
            JsonNode root = objectMapper.readTree(event.getPayload());
            if (root.has("notification_id")) {
                UUID notifId = UUID.fromString(root.get("notification_id").asText());
                return notificationRepository.findById(notifId).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not parse notification_id from outbox payload: {}", event.getPayload());
        }
        return null;
    }
}