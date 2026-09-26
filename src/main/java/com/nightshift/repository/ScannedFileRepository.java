package com.nightshift.repository;

import com.nightshift.model.entity.ScannedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScannedFileRepository extends JpaRepository<ScannedFile, UUID> {

    /**
     * Looks up the checkpoint record for a specific file within a log source,
     * used by the incremental reader to decide the byte offset to resume from.
     */
    Optional<ScannedFile> findByLogSourceIdAndRelativePath(UUID logSourceId, String relativePath);
}
