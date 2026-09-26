package com.nightshift.model.entity;

import com.nightshift.model.enums.AgentRole;
import com.nightshift.model.enums.StepType;
import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Every model call and tool call recorded for audit and evaluation replay.
 */
@Entity
@Table(name = "agent_step")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentStep {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_run_id")
    private ScanRun scanRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_role", nullable = false, length = 24)
    private AgentRole agentRole;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 16)
    private StepType stepType;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "input_summary", columnDefinition = "text")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "text")
    private String outputSummary;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

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
