package com.nightshift.service.agent;

import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.ScanRun;

/**
 * Verifier Agent (Step 7 in the pipeline).
 *
 * <p>Deliberately adversarial second-pass agent that verifies whether a proposed
 * patch is grounded in the provided source code and accurately addresses the
 * triage claim without unsupported claims or regressions.
 */
public interface VerifierAgent {

    /**
     * Verifies the proposed patch against the original source code and triage claims.
     */
    boolean verify(PatchProposal proposal, Incident incident, CodeLocation codeLocation);

    /**
     * Verifies the proposed patch, auditing under the specified scan run.
     */
    boolean verify(PatchProposal proposal, Incident incident, CodeLocation codeLocation, ScanRun scanRun);
}