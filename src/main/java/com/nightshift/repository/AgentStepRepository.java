package com.nightshift.repository;

import com.nightshift.model.entity.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {
    List<AgentStep> findByIncidentIdOrderByStepIndexAsc(UUID incidentId);
}
