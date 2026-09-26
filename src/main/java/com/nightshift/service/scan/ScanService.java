package com.nightshift.service.scan;

import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.TriggerSource;

/**
 * Orchestrates the full incremental scan pipeline:
 * reader → parser → fingerprinter → upsert incidents + occurrences → update scan run counters.
 */
public interface ScanService {

    /**
     * Executes a full scan over all enabled log sources.
     *
     * @param triggerSource who triggered this scan
     * @return the completed {@link ScanRun} with counters filled in
     */
    ScanRun runScan(TriggerSource triggerSource);

    /**
     * Resets scanned file checkpoints (and optionally prior scan/incident data),
     * enabling a fresh scan over all log sources.
     *
     * @param resetData if true, clears previous scan runs and incidents; if false, only resets file offsets
     */
    void resetCheckpoints(boolean resetData);

    /**
     * Executes a scan, optionally resetting checkpoints and prior data beforehand.
     */
    default ScanRun runScan(TriggerSource triggerSource, boolean resetCheckpoints) {
        if (resetCheckpoints) {
            resetCheckpoints(true);
        }
        return runScan(triggerSource);
    }
}
