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
}
