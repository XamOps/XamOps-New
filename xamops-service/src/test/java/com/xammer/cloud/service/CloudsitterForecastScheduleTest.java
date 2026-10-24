package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The forecast-service schedule must use the honest p95 as the predicted-active headroom
 * (yhat_upper is only p90) and must ask for the CPU method explicitly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudsitterForecastScheduleTest {

    @Mock com.xammer.cloud.repository.CloudsitterPolicyRepository policyRepo;
    @Mock com.xammer.cloud.repository.CloudsitterAssignmentRepository assignmentRepo;
    @Mock AwsClientProvider awsClientProvider;
    @Mock com.xammer.cloud.repository.CloudAccountRepository accountRepo;
    @Mock TenantService tenantService;
    @Mock AwsPricingService pricingService;
    @Mock EmailService emailService;
    @Mock CloudsitterEmailBuilder emailBuilder;
    @Mock RestTemplate restTemplate;

    private CloudsitterService service;

    /** 2026-01-05 is a Monday, so this slot is grid[0][10]. */
    private static final String MONDAY_10_UTC = "2026-01-05T10:00:00Z";

    @BeforeEach
    void setUp() {
        service = new CloudsitterService(policyRepo, assignmentRepo, awsClientProvider, accountRepo,
                new ObjectMapper(), tenantService, pricingService, emailService, emailBuilder);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "forecastServiceUrl", "http://forecast:5002");
    }

    private static List<Map<String, Object>> series(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant start = Instant.parse("2025-12-01T00:00:00Z");
        for (int i = 0; i < n; i++) {
            out.add(Map.of("ds", start.plusSeconds(3600L * i).toString(), "y", 3.0));
        }
        return out;
    }

    private void respond(String json) {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(json);
    }

    private static String success(String point) {
        return "{\"status\":\"success\",\"method\":\"histogram\",\"fallback_reason\":null,"
                + "\"trend_direction\":\"STABLE\",\"forecast\":[" + point + "]}";
    }

    @Test
    @DisplayName("p95 above the idle threshold marks the slot active even when yhat_upper is below it")
    void readsP95() {
        respond(success("{\"ds\":\"" + MONDAY_10_UTC + "\",\"yhat\":2.0,\"yhat_upper\":4.0,\"p90\":4.0,\"p95\":6.0}"));
        boolean[][] grid = service.getForecastSchedule(series(96));
        assertThat(grid).isNotNull();
        assertThat(grid[0][10]).isTrue();
    }

    @Test
    @DisplayName("p95 below the idle threshold leaves the slot inactive even when yhat_upper is above it")
    void p95WinsOverYhatUpper() {
        respond(success("{\"ds\":\"" + MONDAY_10_UTC + "\",\"yhat\":2.0,\"yhat_upper\":6.0,\"p90\":6.0,\"p95\":4.0}"));
        boolean[][] grid = service.getForecastSchedule(series(96));
        assertThat(grid).isNotNull();
        assertThat(grid[0][10]).isFalse();
    }

    @Test
    @DisplayName("older forecast-service builds without p95 fall back to yhat_upper")
    void fallsBackToYhatUpperWhenP95Absent() {
        respond("{\"status\":\"success\",\"trend_direction\":\"STABLE\",\"forecast\":["
                + "{\"ds\":\"" + MONDAY_10_UTC + "\",\"yhat\":2.0,\"yhat_lower\":1.0,\"yhat_upper\":6.0}]}");
        boolean[][] grid = service.getForecastSchedule(series(96));
        assertThat(grid).isNotNull();
        assertThat(grid[0][10]).isTrue();
    }

    @Test
    @DisplayName("request goes to /forecast/usage with resource=cpu and periods=336")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendsCpuResource() {
        respond(success("{\"ds\":\"" + MONDAY_10_UTC + "\",\"p95\":1.0,\"yhat_upper\":1.0}"));
        List<Map<String, Object>> data = series(96);
        service.getForecastSchedule(data);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(url.capture(), entity.capture(), eq(String.class));
        assertThat(url.getValue()).isEqualTo("http://forecast:5002/forecast/usage");
        Map<String, Object> body = (Map<String, Object>) entity.getValue().getBody();
        assertThat(body).containsEntry("resource", "cpu").containsEntry("periods", 336).containsEntry("data", data);
    }

    @Test
    @DisplayName("an error status returns null (threshold baseline is used)")
    void errorStatusReturnsNull() {
        respond("{\"status\":\"error\",\"message\":\"boom\"}");
        assertThat(service.getForecastSchedule(series(96))).isNull();
    }

    @Test
    @DisplayName("a RestTemplate failure returns null (threshold baseline is used)")
    void exceptionReturnsNull() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        assertThat(service.getForecastSchedule(series(96))).isNull();
    }

    @Test
    @DisplayName("fewer than 48 points returns null without calling the forecast service")
    void shortSeriesSkipsCall() {
        assertThat(service.getForecastSchedule(series(47))).isNull();
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }
}
