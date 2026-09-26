package com.nightshift.repository;

import com.nightshift.model.entity.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByFingerprint(String fingerprint);
}
