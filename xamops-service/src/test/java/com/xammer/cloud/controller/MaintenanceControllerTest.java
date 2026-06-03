package com.xammer.cloud.controller;

import com.xammer.cloud.service.MaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceControllerTest {

    @Mock
    private MaintenanceService maintenanceService;

    private MaintenanceController controller;

    @BeforeEach
    void setUp() {
        controller = new MaintenanceController(maintenanceService);
    }

    @Test
    void getStatus_returnsActiveFalse_whenInactive() {
        when(maintenanceService.isActive()).thenReturn(false);

        ResponseEntity<Map<String, Boolean>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("active", false);
    }

    @Test
    void getStatus_returnsActiveTrue_whenEnabled() {
        when(maintenanceService.isActive()).thenReturn(true);

        ResponseEntity<Map<String, Boolean>> response = controller.getStatus();

        assertThat(response.getBody()).containsEntry("active", true);
    }

    @Test
    void enable_callsServiceEnableAndReturns200() {
        ResponseEntity<Void> response = controller.enable();

        verify(maintenanceService).enable();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void disable_callsServiceDisableAndReturns200() {
        ResponseEntity<Void> response = controller.disable();

        verify(maintenanceService).disable();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void health_returns200WithStatusUp() {
        ResponseEntity<Map<String, String>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
