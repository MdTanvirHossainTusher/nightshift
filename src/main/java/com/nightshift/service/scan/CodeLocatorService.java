package com.nightshift.service.scan;

import com.nightshift.model.entity.CodeLocation;
import com.nightshift.model.entity.Incident;
import com.nightshift.model.entity.ScanRun;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Deterministic code locator (Step 5 in the pipeline).
 *
 * <p>Maps stack traces or application logger names to concrete source files
 * and line numbers in the target repository.
 */
public interface CodeLocatorService {

    /**
     * Resolves the source code location for an incident against the default target repository.
     *
     * @param incident the incident to locate code for
     * @return the resolved {@link CodeLocation} or empty if unlocatable / non-application
     */
    Optional<CodeLocation> locate(Incident incident);

    /**
     * Resolves the source code location for an incident, optionally auditing against a scan run.
     *
     * @param incident the incident to locate code for
     * @param scanRun  optional scan run for agent step auditing
     * @return the resolved {@link CodeLocation} or empty if unlocatable / non-application
     */
    Optional<CodeLocation> locate(Incident incident, ScanRun scanRun);

    /**
     * Resolves the source code location for an incident in the specified target repository.
     *
     * @param incident       the incident to locate code for
     * @param scanRun        optional scan run for agent step auditing
     * @param targetRepoPath explicit path to the target repository directory
     * @return the resolved {@link CodeLocation} or empty if unlocatable / non-application
     */
    Optional<CodeLocation> locate(Incident incident, ScanRun scanRun, Path targetRepoPath);
}
