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

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CloudsitterRecommendationDto;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsForMetricRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsForMetricResponse;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    /** A shaped (per-hour) response, as returned for memory/AutoETS. */
    private static String success(String point) {
        return "{\"status\":\"success\",\"method\":\"ets\",\"fallback_reason\":null,"
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

    // --- method=histogram (flat) must not replace the per-hour cpuActive baseline ---

    /** 336 future hourly points with the same quantiles everywhere, as the CPU histogram returns. */
    private static String flatHistogramResponse(double p95) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        String pts = IntStream.range(0, 336).mapToObj(i -> String.format(java.util.Locale.ROOT,
                "{\"ds\":\"%s\",\"yhat\":%.2f,\"yhat_lower\":%.2f,\"yhat_upper\":%.2f,"
                        + "\"p50\":%.2f,\"p90\":%.2f,\"p95\":%.2f}",
                start.plus(i, ChronoUnit.HOURS), p95 * 0.6, p95 * 0.1, p95 * 0.9, p95 * 0.6, p95 * 0.9, p95))
                .collect(Collectors.joining(","));
        return "{\"status\":\"success\",\"method\":\"histogram\",\"fallback_reason\":null,"
                + "\"resource\":\"cpu\",\"trend_direction\":\"STABLE\",\"forecast\":[" + pts + "]}";
    }

    @Test
    @DisplayName("method=histogram returns null so the per-hour cpuActive baseline is used")
    void histogramMethodReturnsNull() {
        respond(flatHistogramResponse(20.0));
        assertThat(service.getForecastSchedule(series(96))).isNull();
    }

    @Test
    @DisplayName("a shaped AutoETS (method=ets) response still feeds the grid per hour")
    void etsShapedResponseFeedsGrid() {
        // Monday 10:00 above threshold, Monday 03:00 below it.
        respond(success("{\"ds\":\"" + MONDAY_10_UTC + "\",\"p95\":12.0,\"yhat_upper\":10.0},"
                + "{\"ds\":\"2026-01-05T03:00:00Z\",\"p95\":1.0,\"yhat_upper\":0.8}"));
        boolean[][] grid = service.getForecastSchedule(series(96));
        assertThat(grid).isNotNull();
        assertThat(grid[0][10]).isTrue();
        assertThat(grid[0][3]).isFalse();
    }

    /**
     * Wires EC2/CloudWatch/ELB mocks so getScheduleRecommendation runs end to end on 14 days
     * of hourly CPU (UTC buckets) with no memory/network/disk/ALB signal and no alarms.
     */
    private void wireAws(IntToDoubleFunction cpuByHour) {
        CloudAccount account = new CloudAccount();
        when(accountRepo.findByAwsAccountId("123456789012")).thenReturn(List.of(account));
        Ec2Client ec2 = org.mockito.Mockito.mock(Ec2Client.class);
        CloudWatchClient cw = org.mockito.Mockito.mock(CloudWatchClient.class);
        ElasticLoadBalancingV2Client elb = org.mockito.Mockito.mock(ElasticLoadBalancingV2Client.class);
        when(awsClientProvider.getEc2Client(any(), anyString())).thenReturn(ec2);
        when(awsClientProvider.getCloudWatchClient(any(), anyString())).thenReturn(cw);
        when(awsClientProvider.getElbv2Client(any(), anyString())).thenReturn(elb);
        when(ec2.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(DescribeInstancesResponse.builder().build());
        when(cw.describeAlarmsForMetric(any(DescribeAlarmsForMetricRequest.class)))
                .thenReturn(DescribeAlarmsForMetricResponse.builder().build());
        when(elb.describeTargetGroups(any(DescribeTargetGroupsRequest.class)))
                .thenReturn(DescribeTargetGroupsResponse.builder().build());

        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS);
        List<Instant> ts = new ArrayList<>();
        List<Double> cpu = new ArrayList<>();
        for (int i = 14 * 24; i >= 1; i--) {
            Instant t = end.minus(i, ChronoUnit.HOURS);
            ts.add(t);
            cpu.add(cpuByHour.applyAsDouble(ZonedDateTime.ofInstant(t, ZoneOffset.UTC).getHour()));
        }
        when(cw.getMetricData(any(GetMetricDataRequest.class))).thenReturn(GetMetricDataResponse.builder()
                .metricDataResults(
                        MetricDataResult.builder().id("cpu").timestamps(ts).values(cpu).build(),
                        MetricDataResult.builder().id("cpumax").timestamps(ts).values(cpu).build())
                .build());
    }

    private CloudsitterRecommendationDto recommend() {
        return service.getScheduleRecommendation("123456789012", "i-0abc", "us-east-1", 14, "UTC");
    }

    @Test
    @DisplayName("e2e: flat 336-point histogram response still leaves a diurnal instance (20% day / 1% night) idle at night")
    void diurnalInstanceKeepsIdleHoursWithFlatHistogram() {
        wireAws(h -> h >= 8 && h <= 18 ? 20.0 : 1.0);
        respond(flatHistogramResponse(20.0)); // decayed p95 ~20 for every hour
        CloudsitterRecommendationDto dto = recommend();
        assertThat(dto.getSkipReason()).isNull();
        Map<String, List<Integer>> sched = dto.getRecommendedSchedule();
        assertThat(sched).hasSize(7);
        for (List<Integer> running : sched.values()) {
            // Pre-existing absorb loop re-scans inside an idle run, so only the head of the
            // 00-07 run (00-02) stays idle; the point is that it is no longer 24 h running.
            assertThat(running).hasSizeLessThan(24);
            assertThat(running).doesNotContainAnyElementsOf(List.of(0, 1, 2));
            assertThat(running).containsAll(List.of(8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18));
        }
    }

    @Test
    @DisplayName("e2e: flat low histogram response keeps the per-hour cpuActive baseline of a mostly idle instance")
    void mostlyIdleInstanceKeepsCpuActiveBaselineWithFlatHistogram() {
        wireAws(h -> h >= 9 && h <= 12 ? 8.0 : 1.0); // 30-day avg >= 5% only at 09-12
        respond(flatHistogramResponse(3.0)); // decayed p95 < 5% for every hour
        Map<String, List<Integer>> sched = recommend().getRecommendedSchedule();
        assertThat(sched).hasSize(7);
        for (List<Integer> running : sched.values()) {
            assertThat(running).containsAll(List.of(9, 10, 11, 12));
            assertThat(running).doesNotContainAnyElementsOf(List.of(0, 1, 2));
        }
    }

    @Test
    @DisplayName("e2e: a shaped forecast (old Prophet build, no method field) still drives the baseline")
    void shapedForecastStillDrivesBaseline() {
        wireAws(h -> 1.0); // cpuActive false everywhere
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        String pts = IntStream.range(0, 336).mapToObj(i -> {
            Instant t = start.plus(i, ChronoUnit.HOURS);
            int h = ZonedDateTime.ofInstant(t, ZoneOffset.UTC).getHour();
            double up = h == 10 ? 9.0 : 1.0;
            return "{\"ds\":\"" + t + "\",\"yhat\":1.0,\"yhat_lower\":0.5,\"yhat_upper\":" + up + "}";
        }).collect(Collectors.joining(","));
        respond("{\"status\":\"success\",\"trend_direction\":\"STABLE\",\"forecast\":[" + pts + "]}");
        for (List<Integer> running : recommend().getRecommendedSchedule().values()) {
            assertThat(running).contains(10);
            assertThat(running).doesNotContain(2);
        }
    }
}
