package com.nightshift.model.enums;

public enum Category {
    RESOURCE_LEAK,
    NULL_DEREFERENCE,
    PERFORMANCE,
    RETRY_STORM,
    DATA_LOSS,
    CONCURRENCY,
    MAINTENANCE,
    // Legacy / general categories kept for forward-compatibility
    NULL_POINTER,
    DATABASE,
    NETWORK,
    AUTHENTICATION,
    AUTHORIZATION,
    CONFIGURATION,
    MEMORY,
    EXTERNAL_SERVICE,
    VALIDATION,
    SERIALIZATION,
    FILE_IO,
    UNCATEGORIZED
}
