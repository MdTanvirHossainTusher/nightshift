package com.nightshift.repository;

import com.nightshift.model.entity.PatchProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatchProposalRepository extends JpaRepository<PatchProposal, UUID> {
    Optional<PatchProposal> findTopByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
    List<PatchProposal> findAllByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
