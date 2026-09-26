package com.nightshift.service.agent;

import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.ScanRun;

/**
 * Assigns severity, category, root cause, future impact, and confidence to a single
 * {@link Incident} by calling the configured LLM.
 *
 * <p>Every model call is recorded as an {@code agent_step} row via
 * {@link com.nightshift.util.AgentStepRecorder}.
 *
 * <p>On a JSON parse failure the implementation retries once with a repair instruction
 * appended to the prompt; if the second attempt also fails it sets
 * {@code incident.status = TRIAGE_FAILED} and records the error code
 * {@code LLM_RESPONSE_UNPARSEABLE}.
 */
public interface TriageAgent {

    /**
     * Triages the given incident in-place: populates severity, category, root cause,
     * future impact, recommended action, confidence, triage provider, and triage model.
     * Saves the updated incident and the {@code agent_step} row(s) to the database.
     *
     * @param incident the incident to triage; must already be persisted
     * @param scanRun  the owning scan run (used for the {@code agent_step} foreign key)
     */
    void triage(Incident incident, ScanRun scanRun);
}
