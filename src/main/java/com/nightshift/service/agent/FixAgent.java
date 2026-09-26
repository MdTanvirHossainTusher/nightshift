package com.nightshift.service.agent;

import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.ScanRun;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Fix Agent (Step 6 in the pipeline).
 *
 * <p>Produces a minimal, bounded unified diff for a located incident and validates
 * it against safety guards before creating a {@link PatchProposal} in {@code DRAFT} status.
 */
public interface FixAgent {

    /**
     * Generates and validates a patch proposal for the incident and code location.
     */
    Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation);

    /**
     * Generates and validates a patch proposal, auditing under the given scan run.
     */
    Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun);

    /**
     * Generates and validates a patch proposal against an explicit repository directory.
     */
    Optional<PatchProposal> proposeFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun, Path targetRepoPath);

    /**
     * Second opinion loop: regenerates a fix after the verifier rejected {@code rejected},
     * feeding the verifier's notes and the rejected diff back to the model.
     *
     * @return the revised proposal (status DRAFT), or empty when the model offers no change
     * @throws com.nightshift.exception.PatchRejectedException if the revision fails the patch guard
     */
    Optional<PatchProposal> reviseFix(Incident incident, CodeLocation codeLocation, ScanRun scanRun,
                                      PatchProposal rejected, String verifierNotes);
}
