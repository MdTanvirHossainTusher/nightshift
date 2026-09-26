package com.nightshift.payload.response;

import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.enums.PatchStatus;
import com.nightshift.model.enums.VerifierVerdict;

import java.time.Instant;
import java.util.UUID;

public record PatchProposalResponse(
        UUID id,
        PatchStatus status,
        String unifiedDiff,
        String rationale,
        String testPlan,
        int filesChanged,
        int linesAdded,
        int linesRemoved,
        String provider,
        String model,
        VerifierVerdict verifierVerdict,
        String verifierNotes,
        String rejectionCode,
        int attempt,
        Instant createdAt
) {
    public static PatchProposalResponse from(PatchProposal p) {
        if (p == null) return null;
        return new PatchProposalResponse(
                p.getId(),
                p.getStatus(),
                p.getUnifiedDiff(),
                p.getRationale(),
                p.getTestPlan(),
                p.getFilesChanged(),
                p.getLinesAdded(),
                p.getLinesRemoved(),
                p.getProvider(),
                p.getModel(),
                p.getVerifierVerdict(),
                p.getVerifierNotes(),
                p.getRejectionCode(),
                p.getAttempt(),
                p.getCreatedAt()
        );
    }
}