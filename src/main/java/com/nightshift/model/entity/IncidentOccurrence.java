package com.nightshift.model.entity;

import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single raw log line that matched a fingerprint, kept as evidence for the incident.
 */
@Entity
@Table(name = "incident_occurrence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentOccurrence {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_run_id")
    private ScanRun scanRun;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "log_file", nullable = false, columnDefinition = "text")
    private String logFile;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "thread_name", length = 128)
    private String threadName;

    @Column(name = "raw_line", nullable = false, columnDefinition = "text")
    private String rawLine;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7Util.create();
        }
    }
}
