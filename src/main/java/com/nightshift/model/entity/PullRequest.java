package com.nightshift.model.entity;

import com.nightshift.model.enums.PrState;
import com.nightshift.util.UuidV7Util;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A GitHub pull request opened by the Publish agent for a patch proposal.
 */
@Entity
@Table(name = "pull_request",
        uniqueConstraints = @UniqueConstraint(name = "uq_pull_request_branch",
                columnNames = {"repo_full_name", "branch_name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PullRequest {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patch_proposal_id")
    private PatchProposal patchProposal;

    @Column(name = "provider", nullable = false, length = 16)
    @Builder.Default
    private String provider = "GITHUB";

    @Column(name = "repo_full_name", nullable = false, length = 160)
    private String repoFullName;

    @Column(name = "branch_name", nullable = false, length = 200)
    private String branchName;

    @Column(name = "base_branch", nullable = false, length = 120)
    @Builder.Default
    private String baseBranch = "main";

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url", columnDefinition = "text")
    private String prUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private PrState state;

    @Column(name = "assignee_handle", length = 64)
    private String assigneeHandle;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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
