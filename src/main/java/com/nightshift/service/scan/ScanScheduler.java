package com.nightshift.service.scan;

import com.nightshift.model.enums.TriggerSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled overnight scan runner.
 * Triggers automated log analysis and remediation at the configured cron schedule (default: 02:00 Dhaka time).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanScheduler {

    private final ScanService scanService;

    @Scheduled(cron = "${nightshift.scan.cron:0 0 2 * * *}")
    public void scheduleOvernightScan() {
        log.info("ScanScheduler: triggering overnight scheduled scan...");
        try {
            scanService.runScan(TriggerSource.SCHEDULE);
            log.info("ScanScheduler: scheduled scan completed successfully.");
        } catch (Exception e) {
            log.warn("ScanScheduler: scheduled scan could not be executed or is already running: {}", e.getMessage());
        }
    }
}