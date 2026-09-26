package com.nightshift.model.entity;

import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A folder of rotated log files that Nightshift watches.
 */
@Entity
@Table(name = "log_source")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogSource {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "root_path", nullable = false, columnDefinition = "text")
    private String rootPath;

    @Column(name = "file_glob", nullable = false, length = 128)
    @Builder.Default
    private String fileGlob = "**/*.log";

    @Column(name = "service_name", length = 64)
    private String serviceName;

    @Column(name = "target_repo", length = 128)
    private String targetRepo;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7Util.create();
        }
    }
}
