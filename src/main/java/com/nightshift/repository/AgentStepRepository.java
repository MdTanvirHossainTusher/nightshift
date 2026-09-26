package com.nightshift.repository;

import com.nightshift.model.entity.AgentStep;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {
    List<AgentStep> findByIncidentIdOrderByStepIndexAsc(UUID incidentId);
    List<AgentStep> findByScanRunIdOrderByCreatedAtAsc(UUID scanRunId);
    List<AgentStep> findByScanRunIdAndIncidentIdOrderByStepIndexAsc(UUID scanRunId, UUID incidentId);
    Page<AgentStep> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
