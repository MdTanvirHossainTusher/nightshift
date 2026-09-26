package com.nightshift.repository;

import com.nightshift.model.entity.IncidentOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IncidentOccurrenceRepository extends JpaRepository<IncidentOccurrence, UUID> {

    /** Returns the number of occurrence samples already stored for the given incident. */
    long countByIncidentId(UUID incidentId);
}
