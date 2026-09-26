package com.nightshift.service.outbox;

public record Assignee(
        String name,
        String email,
        String github,
        boolean isDefault
) {}