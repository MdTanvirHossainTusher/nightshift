package com.nightshift.controller;

import com.nightshift.constant.code.ErrorCodes;
import com.nightshift.constant.code.SuccessCodes;
import com.nightshift.model.entity.ScanRun;
import com.nightshift.model.enums.ScanStatus;
import com.nightshift.model.enums.TriggerSource;
import com.nightshift.repository.ScanRunRepository;
import com.nightshift.service.scan.ScanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScanController.class)
class ScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScanService scanService;

    @MockitoBean
    private ScanRunRepository scanRunRepository;

    @Test
    void triggerScan_whenIdle_returns202ScanAccepted() throws Exception {
        when(scanRunRepository.existsByStatus(ScanStatus.RUNNING)).thenReturn(false);

        ScanRun run = ScanRun.builder()
                .id(UUID.randomUUID())
                .triggerSource(TriggerSource.API)
                .status(ScanStatus.COMPLETED)
                .startedAt(Instant.now())
                .incidentsNew(3)
                .linesParsed(1500)
                .build();

        when(scanService.runScan(TriggerSource.API)).thenReturn(run);

        mockMvc.perform(post("/api/v1/scans").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(SuccessCodes.SCAN_ACCEPTED))
                .andExpect(jsonPath("$.data.incidents_new").value(3));
    }

    @Test
    void triggerScan_whenAlreadyRunning_returns400ScanAlreadyRunning() throws Exception {
        when(scanRunRepository.existsByStatus(ScanStatus.RUNNING)).thenReturn(true);

        mockMvc.perform(post("/api/v1/scans").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCodes.SCAN_ALREADY_RUNNING));
    }

    @Test
    void listScans_returns200WithPagedResult() throws Exception {
        ScanRun run = ScanRun.builder()
                .id(UUID.randomUUID())
                .triggerSource(TriggerSource.SCHEDULE)
                .status(ScanStatus.COMPLETED)
                .startedAt(Instant.now())
                .build();

        when(scanRunRepository.findAllByOrderByStartedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run)));

        mockMvc.perform(get("/api/v1/scans").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.pagination.total_count").value(1));
    }

    @Test
    void getScan_whenExists_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        ScanRun run = ScanRun.builder()
                .id(id)
                .triggerSource(TriggerSource.API)
                .status(ScanStatus.COMPLETED)
                .startedAt(Instant.now())
                .build();

        when(scanRunRepository.findById(id)).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/scans/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    void getScan_whenNotFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(scanRunRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/scans/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCodes.NOT_FOUND));
    }
}