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

    @Query("SELECT i FROM Incident i WHERE (:status IS NULL OR i.status = :status) AND (:severity IS NULL OR i.severity = :severity) ORDER BY i.lastSeenAt DESC")
    Page<Incident> findByFilters(@Param("status") IncidentStatus status,
                                 @Param("severity") Severity severity,
                                 Pageable pageable);
}
