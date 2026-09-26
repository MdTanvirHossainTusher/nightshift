package com.nightshift.service.outbox;

import java.util.UUID;

/**
 * Spring application event published immediately after an OutboxEvent is persisted,
 * allowing the OutboxRelay to react after transaction commit.
 */
public record OutboxEventCreatedEvent(UUID eventId) {
}