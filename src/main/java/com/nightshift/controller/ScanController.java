package com.nightshift.controller;

import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.constant.code.SuccessCodes;
import com.nightshift.exception.BadResourceRequestException;
import com.nightshift.exception.ResourceNotFoundException;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.payload.common.ApiResponse;
import com.nightshift.payload.common.PageResult;
import com.nightshift.payload.common.ResponseBuilder;
import com.nightshift.payload.response.ScanRunResponse;
import com.nightshift.repository.ScanRunRepository;
import com.nightshift.service.scan.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Tag(name = "Scans", description = "Endpoints for triggering and inspecting scan runs")
@RestController
@RequestMapping("/api/v1/scans")
@RequiredArgsConstructor
public class ScanController {

    private static final AtomicBoolean SCAN_IN_PROGRESS = new AtomicBoolean(false);

    private final ScanService scanService;
    private final ScanRunRepository scanRunRepository;

    @Operation(summary = "Trigger a new log scan (optionally resetting checkpoints for full re-scan)")
    @PostMapping
    public ResponseEntity<ApiResponse<ScanRunResponse>> triggerScan(
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "false") boolean reset
    ) {
        if (!SCAN_IN_PROGRESS.compareAndSet(false, true)) {
            throw new com.nightshift.exception.ResourceAlreadyExistsException(
                    ErrorCodes.SCAN_ALREADY_RUNNING,
                    "A scan is already in progress. Concurrent scans are not permitted."
            );
        }

        try {
            boolean doReset = force || reset;
            if (doReset) {
                log.info("Force re-scan requested. Resetting scan checkpoints and prior data...");
                scanService.resetCheckpoints(true);
            } else if (scanRunRepository.existsByStatus(ScanStatus.RUNNING)) {
                throw new com.nightshift.exception.ResourceAlreadyExistsException(
                        ErrorCodes.SCAN_ALREADY_RUNNING,
                        "A scan is already in progress in the database."
                );
            }

            ScanRun run = scanService.runScan(TriggerSource.API);
            return ResponseBuilder.accepted(
                    ScanRunResponse.from(run),
                    SuccessCodes.SCAN_ACCEPTED,
                    "Scan request accepted and processed."
            );
        } finally {
            SCAN_IN_PROGRESS.set(false);
        }
    }

    @Operation(summary = "Reset scan checkpoints and clear prior scan data")
    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<String>> resetCheckpoints(
            @RequestParam(defaultValue = "true") boolean resetData
    ) {
        scanService.resetCheckpoints(resetData);
        return ResponseBuilder.ok("Scan checkpoints reset successfully. Next scan will parse all log sources from offset 0.");
    }

    @Operation(summary = "List scan runs with pagination")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ScanRunResponse>>> listScans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<ScanRun> runPage = scanRunRepository.findAllByOrderByStartedAtDesc(PageRequest.of(page, size));
        PageResult<ScanRunResponse> result = PageResult.of(runPage, ScanRunResponse::from);
        return ResponseBuilder.ok(result);
    }

    @Operation(summary = "Get details of a specific scan run")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScanRunResponse>> getScan(@PathVariable UUID id) {
        ScanRun run = scanRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCodes.NOT_FOUND,
                        "Scan run not found: " + id
                ));
        return ResponseBuilder.ok(ScanRunResponse.from(run));
    }
}