package com.nightshift.repository;

import com.nightshift.model.entity.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
    Optional<PullRequest> findByRepoFullNameAndBranchName(String repoFullName, String branchName);
    boolean existsByRepoFullNameAndBranchName(String repoFullName, String branchName);
    Optional<PullRequest> findTopByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
    List<PullRequest> findAllByIncidentId(UUID incidentId);
}
