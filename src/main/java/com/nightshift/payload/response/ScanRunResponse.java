package com.nightshift.payload.response;

import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;

import java.time.Instant;
import java.util.UUID;

public record ScanRunResponse(
        UUID id,
        TriggerSource triggerSource,
        ScanStatus status,
        Instant startedAt,
        Instant finishedAt,
        long linesParsed,
        long bytesRead,
        int filesSeen,
        int filesWithNewData,
        int eventsMatched,
        int incidentsNew,
        int incidentsUpdated,
        int patchesProposed,
        int prsOpened,
        String errorMessage
) {
    public static ScanRunResponse from(ScanRun run) {
        if (run == null) return null;
        return new ScanRunResponse(
                run.getId(),
                run.getTriggerSource(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getLinesParsed(),
                run.getBytesRead(),
                run.getFilesSeen(),
                run.getFilesWithNewData(),
                run.getEventsMatched(),
                run.getIncidentsNew(),
                run.getIncidentsUpdated(),
                run.getPatchesProposed(),
                run.getPrsOpened(),
                run.getErrorMessage()
        );
    }
}