package com.xammer.cloud.controller;

import com.xammer.cloud.service.BuyerUsageService;
import com.xammer.cloud.service.CustomerRegistrationService;
import com.xammer.cloud.service.SnsNotificationService;
import com.xammer.cloud.service.UsageEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA depth-testing: ISV metering API-key check (report 02-bugs-D, BUG D-04).
 *
 * <p>{@code GET /api/metering/customers/{customerId}/events} is {@code permitAll} and only checks
 * that {@code X-Api-Key} is non-blank; the key is never validated against the product.
 */
class MeteringControllerApiKeyQaTest {

    private UsageEventService usageEventService;
    private MeteringController controller;

    @BeforeEach
    void setUp() {
        usageEventService = mock(UsageEventService.class);
        controller = new MeteringController(mock(CustomerRegistrationService.class), usageEventService,
                mock(SnsNotificationService.class), mock(BuyerUsageService.class));
    }

    @Test
    @DisplayName("characterization: missing or blank key is 401 and reads nothing")
    void missingKeyIs401() {
        ResponseEntity<Object> r = controller.getCustomerEvents(null, "cust-1", "prod-1");
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<Object> blank = controller.getCustomerEvents("  ", "cust-1", "prod-1");
        assertThat(blank.getStatusCode().value()).isEqualTo(401);
        verify(usageEventService, never()).getRecentEvents(anyString(), anyString());
    }

    @Test
    @Disabled("BUG D-04: any non-blank X-Api-Key returns another ISV product's per-customer events; the key is never validated")
    @DisplayName("an arbitrary API key must not read customer events")
    void arbitraryKeyIsRejected() {
        when(usageEventService.getEventStats(anyString())).thenReturn(Map.of());
        when(usageEventService.getRecentEvents(anyString(), anyString())).thenReturn(List.of(Map.of("k", "v")));

        ResponseEntity<Object> r = controller.getCustomerEvents("not-a-real-key", "cust-1", "prod-1");

        assertThat(r.getStatusCode().value()).isIn(401, 403);
        verify(usageEventService, never()).getRecentEvents(any(), any());
    }
}
