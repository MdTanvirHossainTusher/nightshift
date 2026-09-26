package com.nightshift.repository;

import com.nightshift.model.entity.PatchProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatchProposalRepository extends JpaRepository<PatchProposal, UUID> {
}
