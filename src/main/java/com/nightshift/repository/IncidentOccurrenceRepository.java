package com.nightshift.repository;

import com.nightshift.model.entity.IncidentOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface IncidentOccurrenceRepository extends JpaRepository<IncidentOccurrence, UUID> {

    /** Returns the number of occurrence samples already stored for the given incident. */
    long countByIncidentId(UUID incidentId);

    /** Returns up to {@code limit} raw log lines for the given incident, ordered by occurrence time. */
    @Query("SELECT o FROM IncidentOccurrence o WHERE o.incident.id = :incidentId ORDER BY o.occurredAt ASC")
    List<IncidentOccurrence> findTopByIncidentId(@Param("incidentId") UUID incidentId,
                                                  org.springframework.data.domain.Pageable pageable);

    @Query("SELECT o.incident.id, o.scanRun.id FROM IncidentOccurrence o " +
           "WHERE o.incident.id IN :incidentIds AND o.scanRun IS NOT NULL " +
           "ORDER BY o.occurredAt DESC")
    List<Object[]> findLatestScanRunPerIncident(@Param("incidentIds") java.util.Collection<UUID> incidentIds);
}
