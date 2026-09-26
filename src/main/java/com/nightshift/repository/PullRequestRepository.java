package com.nightshift.repository;

import com.nightshift.model.entity.PullRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {
}
