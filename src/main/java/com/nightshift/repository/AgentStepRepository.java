package com.nightshift.repository;

import com.nightshift.model.entity.AgentStep;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Trajectory reads fetch the incident eagerly: {@code AgentStepResponse} reads its title after
 * the transaction has closed ({@code open-in-view} is off), so a lazy proxy would throw.
 */
public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {
    @EntityGraph(attributePaths = "incident")
    List<AgentStep> findByIncidentIdOrderByStepIndexAsc(UUID incidentId);

    @EntityGraph(attributePaths = "incident")
    List<AgentStep> findByScanRunIdOrderByCreatedAtAsc(UUID scanRunId);

    @EntityGraph(attributePaths = "incident")
    List<AgentStep> findByScanRunIdAndIncidentIdOrderByStepIndexAsc(UUID scanRunId, UUID incidentId);

    @EntityGraph(attributePaths = "incident")
    Page<AgentStep> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
