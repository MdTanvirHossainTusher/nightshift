package com.nightshift.repository;

import com.nightshift.model.entity.OutboxEvent;
import com.nightshift.model.enums.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.attempts < :maxAttempts ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEvents(@Param("status") OutboxStatus status, @Param("maxAttempts") int maxAttempts, Pageable pageable);

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);
}