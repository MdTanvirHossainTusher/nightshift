package com.nightshift.model.entity;

import com.nightshift.model.enums.Category;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;
import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One distinct problem, de-duplicated across all log files by fingerprint.
 */
@Entity
@Table(name = "incident")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Incident {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "fingerprint", nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "service_name", length = 64)
    private String serviceName;

    @Column(name = "logger_name", length = 256)
    private String loggerName;

    @Column(name = "log_level", nullable = false, length = 8)
    private String logLevel;

    @Column(name = "exception_type", length = 256)
    private String exceptionType;

    @Column(name = "normalized_message", nullable = false, columnDefinition = "text")
    private String normalizedMessage;

    @Column(name = "sample_message", columnDefinition = "text")
    private String sampleMessage;

    @Column(name = "sample_stacktrace", columnDefinition = "text")
    private String sampleStacktrace;

    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private int occurrenceCount = 0;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @Column(name = "severity_rationale", columnDefinition = "text")
    private String severityRationale;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 48)
    private Category category;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Column(name = "future_impact", columnDefinition = "text")
    private String futureImpact;

    @Column(name = "recommended_action", columnDefinition = "text")
    private String recommendedAction;

    @Column(name = "confidence", columnDefinition = "numeric(3,2)")
    private BigDecimal confidence;

    @Column(name = "triaged_at")
    private Instant triagedAt;

    @Column(name = "triage_provider", length = 32)
    private String triageProvider;

    @Column(name = "triage_model", length = 64)
    private String triageModel;

    @Column(name = "muted", nullable = false)
    @Builder.Default
    private boolean muted = false;

    @Column(name = "mute_reason", columnDefinition = "text")
    private String muteReason;

    @Column(name = "assignee_email", length = 256)
    private String assigneeEmail;

    @Column(name = "assignee_handle", length = 64)
    private String assigneeHandle;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7Util.create();
        }
    }
}
