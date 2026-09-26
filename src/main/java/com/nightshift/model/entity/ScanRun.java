package com.nightshift.model.entity;

import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One execution of the Nightshift scan pipeline.
 */
@Entity
@Table(name = "scan_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanRun {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 16)
    private TriggerSource triggerSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScanStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "files_seen", nullable = false)
    @Builder.Default
    private int filesSeen = 0;

    @Column(name = "files_with_new_data", nullable = false)
    @Builder.Default
    private int filesWithNewData = 0;

    @Column(name = "bytes_read", nullable = false)
    @Builder.Default
    private long bytesRead = 0L;

    @Column(name = "lines_parsed", nullable = false)
    @Builder.Default
    private long linesParsed = 0L;

    @Column(name = "events_matched", nullable = false)
    @Builder.Default
    private int eventsMatched = 0;

    @Column(name = "incidents_new", nullable = false)
    @Builder.Default
    private int incidentsNew = 0;

    @Column(name = "incidents_updated", nullable = false)
    @Builder.Default
    private int incidentsUpdated = 0;

    @Column(name = "patches_proposed", nullable = false)
    @Builder.Default
    private int patchesProposed = 0;

    @Column(name = "prs_opened", nullable = false)
    @Builder.Default
    private int prsOpened = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7Util.create();
        }
    }
}
