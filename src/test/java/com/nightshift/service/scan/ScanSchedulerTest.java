package com.nightshift.service.scan;

import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.exception.ResourceAlreadyExistsException;
import com.nightshift.model.enums.TriggerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScanSchedulerTest {

    @Mock
    private ScanService scanService;

    @InjectMocks
    private ScanScheduler scanScheduler;

    @Test
    void scheduleOvernightScan_triggersScheduleRun() {
        scanScheduler.scheduleOvernightScan();
        verify(scanService, times(1)).runScan(TriggerSource.SCHEDULE);
    }

    @Test
    void scheduleOvernightScan_whenAlreadyRunning_swallowsExceptionGracefully() {
        doThrow(new ResourceAlreadyExistsException(ErrorCodes.SCAN_ALREADY_RUNNING, "Scan running"))
                .when(scanService).runScan(TriggerSource.SCHEDULE);

        scanScheduler.scheduleOvernightScan();
        verify(scanService, times(1)).runScan(TriggerSource.SCHEDULE);
    }
}