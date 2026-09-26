package com.nightshift.service.publish;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.PatchProposal;
import com.nightshift.model.entity.PullRequest;
import com.nightshift.model.entity.ScanRun;

/**
 * Publisher service (Step 8 in the pipeline).
 *
 * <p>Handles branch creation, conventional commit, remote push, and GitHub PR opening.
 */
public interface PublisherService {

    /**
     * Publishes a verified patch proposal as a pull request.
     */
    PullRequest publish(Incident incident, PatchProposal proposal);

    /**
     * Publishes a verified patch proposal as a pull request, auditing under the scan run.
     */
    PullRequest publish(Incident incident, PatchProposal proposal, ScanRun scanRun);
}