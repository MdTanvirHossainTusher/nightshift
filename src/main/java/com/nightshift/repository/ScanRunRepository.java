package com.nightshift.repository;

import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ScanRunRepository extends JpaRepository<ScanRun, UUID> {

    /**
     * Returns true when any scan run is currently in the given status.
     * Primarily used to enforce the single-active-run guard (status = RUNNING).
     */
    boolean existsByStatus(ScanStatus status);

    Page<ScanRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
