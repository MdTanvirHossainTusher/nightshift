package com.nightshift.repository;

import com.nightshift.model.entity.CodeLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeLocationRepository extends JpaRepository<CodeLocation, UUID> {
    Optional<CodeLocation> findByIncidentId(UUID incidentId);
    List<CodeLocation> findAllByIncidentId(UUID incidentId);
    void deleteByIncidentId(UUID incidentId);
}
