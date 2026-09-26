package com.nightshift.repository;

import com.nightshift.model.entity.LogSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LogSourceRepository extends JpaRepository<LogSource, UUID> {
}
