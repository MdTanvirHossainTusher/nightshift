package com.nightshift.payload.response;

import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.enums.PrState;

import java.time.Instant;
import java.util.UUID;

public record PullRequestResponse(
        UUID id,
        String provider,
        String repoFullName,
        String branchName,
        String baseBranch,
        Integer prNumber,
        String prUrl,
        PrState state,
        String assigneeHandle,
        Instant openedAt,
        Instant closedAt
) {
    public static PullRequestResponse from(PullRequest pr) {
        if (pr == null) return null;
        return new PullRequestResponse(
                pr.getId(),
                pr.getProvider(),
                pr.getRepoFullName(),
                pr.getBranchName(),
                pr.getBaseBranch(),
                pr.getPrNumber(),
                pr.getPrUrl(),
                pr.getState(),
                pr.getAssigneeHandle(),
                pr.getOpenedAt(),
                pr.getClosedAt()
        );
    }
}