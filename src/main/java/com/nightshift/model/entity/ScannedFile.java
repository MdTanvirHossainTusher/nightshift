package com.nightshift.model.entity;

import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the byte offset already consumed from each log file, making scans incremental.
 */
@Entity
@Table(name = "scanned_file",
        uniqueConstraints = @UniqueConstraint(name = "uq_scanned_file_path",
                columnNames = {"log_source_id", "relative_path"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScannedFile {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "log_source_id", nullable = false)
    private LogSource logSource;

    @Column(name = "relative_path", nullable = false, columnDefinition = "text")
    private String relativePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "byte_offset", nullable = false)
    @Builder.Default
    private long byteOffset = 0L;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_scanned_at", nullable = false)
    private Instant lastScannedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_scan_run_id")
    private ScanRun lastScanRun;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7Util.create();
        }
    }
}
