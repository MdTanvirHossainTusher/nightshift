package com.nightshift.payload.response;

import com.nightshift.model.entity.LogSource;

import java.time.Instant;
import java.util.UUID;

public record LogSourceResponse(
        UUID id,
        String name,
        String serviceName,
        String rootPath,
        String fileGlob,
        String targetRepo,
        boolean enabled,
        Instant createdAt
) {
    public static LogSourceResponse from(LogSource src) {
        if (src == null) return null;
        return new LogSourceResponse(
                src.getId(),
                src.getName(),
                src.getServiceName(),
                src.getRootPath(),
                src.getFileGlob(),
                src.getTargetRepo(),
                src.isEnabled(),
                src.getCreatedAt()
        );
    }
}