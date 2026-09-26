package com.nightshift.repository;

import com.nightshift.model.entity.CodeLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CodeLocationRepository extends JpaRepository<CodeLocation, UUID> {
}
