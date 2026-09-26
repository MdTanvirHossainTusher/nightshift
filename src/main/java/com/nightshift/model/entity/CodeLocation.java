package com.nightshift.model.entity;

import com.nightshift.model.enums.ConfidenceLevel;
import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A resolved source-code location linked to an incident.
 */
@Entity
@Table(name = "code_location")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeLocation {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Column(name = "target_repo", nullable = false, length = 128)
    private String targetRepo;

    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    private String filePath;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "frame_signature", columnDefinition = "text")
    private String frameSignature;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 8)
    private ConfidenceLevel confidence;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

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
