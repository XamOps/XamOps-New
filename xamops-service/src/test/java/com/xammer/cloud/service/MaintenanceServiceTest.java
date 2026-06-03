package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceServiceTest {

    private MaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new MaintenanceService();
    }

    @Test
    void isActive_isFalseByDefault() {
        assertThat(service.isActive()).isFalse();
    }

    @Test
    void enable_setsActiveTrue() {
        service.enable();
        assertThat(service.isActive()).isTrue();
    }

    @Test
    void disable_setsActiveFalse() {
        service.enable();
        service.disable();
        assertThat(service.isActive()).isFalse();
    }
}
