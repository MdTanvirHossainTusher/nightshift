package com.nightshift.repository;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.enums.IncidentStatus;
import com.nightshift.model.enums.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByFingerprint(String fingerprint);

    @Query(value = "SELECT DISTINCT i FROM Incident i " +
           "LEFT JOIN IncidentOccurrence io ON io.incident = i " +
           "WHERE (:status IS NULL OR i.status = :status) " +
           "  AND (:severity IS NULL OR i.severity = :severity) " +
           "  AND (:scanRunId IS NULL OR io.scanRun.id = :scanRunId) " +
           "ORDER BY i.lastSeenAt DESC",
           countQuery = "SELECT COUNT(DISTINCT i) FROM Incident i " +
           "LEFT JOIN IncidentOccurrence io ON io.incident = i " +
           "WHERE (:status IS NULL OR i.status = :status) " +
           "  AND (:severity IS NULL OR i.severity = :severity) " +
           "  AND (:scanRunId IS NULL OR io.scanRun.id = :scanRunId)")
    Page<Incident> findByFilters(@Param("status") IncidentStatus status,
                                 @Param("severity") Severity severity,
                                 @Param("scanRunId") UUID scanRunId,
                                 Pageable pageable);

    default Page<Incident> findByFilters(IncidentStatus status, Severity severity, Pageable pageable) {
        return findByFilters(status, severity, null, pageable);
    }
}
