package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.AutomationActivityController;
import com.xammer.cloud.service.automation.workload.api.AutomationActivityService;
import com.xammer.cloud.service.automation.workload.api.dto.ActivityPageDto;
import com.xammer.cloud.service.automation.workload.api.dto.ActivitySummaryDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The activity feed's contract: tenancy exactly like endpoint 1, every parameter validated with its
 * field named, the filters reaching the query, the cursor, a database fault reported as one, and the
 * entries' wire shape for the rows UAT holds today and the rows this build adds. Everything below
 * the controller is the real service over a mocked repository, so nothing reaches a database.
 */
class AutomationActivityControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/eks/automation/activity/xamops-test-1";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";
    private static final ClusterTarget TARGET = new ClusterTarget(7L, 42L, AWS_ID, "xamops-test-1", "ap-south-1");

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private WorkloadVerticalActionRepository actionRepository;
    private AutomationActivityService service;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        actionRepository = mock(WorkloadVerticalActionRepository.class);
        service = spy(new AutomationActivityService(actionRepository, new WorkloadAutoscalingProperties(true, false)));

        Client client = new Client();
        client.setId(7L);
        CloudAccount account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setStatus("CONNECTED");
        account.setClient(client);
        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);
        when(accessService.requireAwsAccess(any(), eq(FOREIGN_ID)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));

        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-1");
        config.setRegion("ap-south-1");
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(3L, "xamops-test-1"))
                .thenReturn(Optional.of(config));

        ClientUserDetails principal = new ClientUserDetails("user@example.com", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 10L, 7L, "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a page of the tenant's cluster: newest first, who, result, before -> after and the proof")
    void aPageIsServed() throws Exception {
        when(actionRepository.findActivityPage(eq(7L), eq(42L), isNull(), isNull(), eq(51)))
                .thenReturn(List.of(putBack(3L), hpaChange(2L), legacyDeferral(1L)));

        mvc().perform(get(BASE + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.enabled").value(true))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(3))
                // A put-back deferral recorded by this build, by the automatic loop.
                .andExpect(jsonPath("$.entries[0].id").value(3))
                .andExpect(jsonPath("$.entries[0].feature").value("VERTICAL"))
                .andExpect(jsonPath("$.entries[0].origin").value("AUTO"))
                .andExpect(jsonPath("$.entries[0].who").value("XamOps (automatic)"))
                .andExpect(jsonPath("$.entries[0].action").value("RESIZE"))
                .andExpect(jsonPath("$.entries[0].target.name").value("overprovisioned-api"))
                .andExpect(jsonPath("$.entries[0].outcome").value("NOT_CHANGED"))
                .andExpect(jsonPath("$.entries[0].result").value("PUT_BACK"))
                .andExpect(jsonPath("$.entries[0].terminal").value("DEFERRED"))
                .andExpect(jsonPath("$.entries[0].change.containers[0].beforeBasis").value("OBSERVED"))
                .andExpect(jsonPath("$.entries[0].change.containers[0].before.cpuRequestMillis").value(1000))
                .andExpect(jsonPath("$.entries[0].change.containers[0].after.cpuRequestMillis").value(1000))
                .andExpect(jsonPath("$.entries[0].change.containers[0].requested.cpuRequestMillis").value(25))
                .andExpect(jsonPath("$.entries[0].proof.reverted").value(true))
                .andExpect(jsonPath("$.entries[0].proof.pods[0].pod").value("overprovisioned-api-1"))
                .andExpect(jsonPath("$.entries[0].proof.pods[0].restartsBefore").value(0))
                .andExpect(jsonPath("$.entries[0].proof.pods[0].restartsAfter").value(0))
                .andExpect(jsonPath("$.entries[0].proof.pods[0].revert.state").value("REVERTED"))
                .andExpect(jsonPath("$.entries[0].reference").value("corr-3"))
                // An HPA change, by a user.
                .andExpect(jsonPath("$.entries[1].feature").value("HPA"))
                .andExpect(jsonPath("$.entries[1].who").value("A user"))
                .andExpect(jsonPath("$.entries[1].action").value("HPA_CHANGE"))
                .andExpect(jsonPath("$.entries[1].result").value("APPLIED"))
                .andExpect(jsonPath("$.entries[1].change.hpa.before.maxReplicas").value(5))
                .andExpect(jsonPath("$.entries[1].change.hpa.after.maxReplicas").value(8))
                .andExpect(jsonPath("$.entries[1].proof").doesNotExist())
                // The throttled-encoder row UAT holds from before the activity log: Failed, with its detail.
                .andExpect(jsonPath("$.entries[2].result").value("FAILED"))
                .andExpect(jsonPath("$.entries[2].terminal").doesNotExist())
                .andExpect(jsonPath("$.entries[2].change.containers[0].beforeBasis").value("REQUESTED"))
                .andExpect(jsonPath("$.entries[2].change.containers[0].before.cpuRequestMillis").value(63))
                .andExpect(jsonPath("$.entries[2].change.containers[0].after.cpuRequestMillis").value(10))
                .andExpect(jsonPath("$.entries[2].proof").doesNotExist())
                .andExpect(jsonPath("$.entries[2].detail")
                        .value(org.hamcrest.Matchers.startsWith("The kubelet deferred the resize")));
    }

    @Test
    @DisplayName("the filters, the search, the range, the limit and the cursor reach the query")
    void theParametersReachTheQuery() throws Exception {
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-10-13T06:00:00Z|17".getBytes(StandardCharsets.UTF_8));

        mvc().perform(get(BASE).param("accountId", AWS_ID).param("region", "ap-south-1")
                        .param("feature", "hpa,VERTICAL").param("result", "APPLIED,put_back").param("q", " php ")
                        .param("from", "2026-10-01T00:00:00Z").param("to", "2026-10-14T05:30:00+05:30")
                        .param("limit", "10").param("cursor", cursor))
                .andExpect(status().isOk());

        ArgumentCaptor<AutomationActivityService.Query> query =
                ArgumentCaptor.forClass(AutomationActivityService.Query.class);
        verify(service).page(eq(TARGET), query.capture());
        assertThat(query.getValue().features())
                .containsExactlyInAnyOrder(AutomationActivityService.Feature.HPA, AutomationActivityService.Feature.VERTICAL);
        assertThat(query.getValue().results())
                .containsExactlyInAnyOrder(AutomationActivityService.Result.APPLIED, AutomationActivityService.Result.PUT_BACK);
        assertThat(query.getValue().nameContains()).isEqualTo("php");
        assertThat(query.getValue().from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(query.getValue().to()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(query.getValue().limit()).isEqualTo(10);
        assertThat(query.getValue().cursor())
                .isEqualTo(new WorkloadVerticalActionRepository.ActivityCursor(Instant.parse("2026-10-13T06:00:00Z"), 17L));
        verify(actionRepository).findActivityPage(eq(7L), eq(42L), any(), eq(query.getValue().cursor()), eq(11));
    }

    @Test
    @DisplayName("a full page carries the cursor of the next one, which continues after its last entry")
    void aFullPageCarriesTheNextCursor() throws Exception {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        for (long id = 3; id >= 1; id--) {
            rows.add(legacyDeferral(id));
        }
        when(actionRepository.findActivityPage(anyLong(), anyLong(), any(), any(), anyInt())).thenReturn(rows);

        MvcResult result = mvc().perform(get(BASE + QUERY + "&limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andReturn();

        String next = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.nextCursor");
        assertThat(next).isNotBlank().hasSizeLessThanOrEqualTo(AutomationActivityService.MAX_CURSOR_LENGTH);
        assertThat(AutomationActivityService.decodeCursor(next))
                .isEqualTo(new WorkloadVerticalActionRepository.ActivityCursor(rows.get(1).getAppliedAt(), 2L));
    }

    @Test
    @DisplayName("another tenant's account is 403 K8S_NO_ACCOUNT_ACCESS, and nothing is looked up")
    void anotherTenantIsRefused() throws Exception {
        mvc().perform(get(BASE + "?accountId=" + FOREIGN_ID + "&region=ap-south-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("K8S_NO_ACCOUNT_ACCESS"));

        verifyNoInteractions(clusterConfigRepository, actionRepository);
    }

    @Test
    @DisplayName("an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED")
    void anUnregisteredClusterIsNotFound() throws Exception {
        mvc().perform(get("/api/eks/automation/activity/elsewhere" + QUERY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));

        verifyNoInteractions(actionRepository);
    }

    @Test
    @DisplayName("every malformed parameter is 400 K8S_INVALID_REQUEST naming it, before any lookup")
    void malformedParametersAreRefused() throws Exception {
        // Each case: the parameters beyond a valid accountId and region, and the field the 400 names.
        List<Map.Entry<Map<String, String>, String>> cases = List.of(
                Map.entry(Map.of("feature", "NODE"), "feature"),
                Map.entry(Map.of("feature", "VERTICAL,"), "feature"),
                Map.entry(Map.of("result", "WON"), "result"),
                Map.entry(Map.of("q", "a".repeat(101)), "q"),
                Map.entry(Map.of("q", "a\u0000pi"), "q"),
                Map.entry(Map.of("from", "yesterday"), "from"),
                Map.entry(Map.of("to", "2026-13-01T00:00:00Z"), "to"),
                Map.entry(Map.of("from", "2026-10-10T00:00:00Z", "to", "2026-10-01T00:00:00Z"), "from"),
                Map.entry(Map.of("from", "2026-06-01T00:00:00Z", "to", "2026-10-01T00:00:00Z"), "to"),
                Map.entry(Map.of("from", "2020-01-01T00:00:00Z"), "from"),
                Map.entry(Map.of("limit", "0"), "limit"),
                Map.entry(Map.of("limit", "201"), "limit"),
                Map.entry(Map.of("limit", "ten"), "limit"),
                Map.entry(Map.of("cursor", "not-a-cursor!"), "cursor"),
                Map.entry(Map.of("cursor", "a".repeat(129)), "cursor"),
                Map.entry(Map.of("accountId", "not-a-number"), "accountId"),
                Map.entry(Map.of("region", "NOT_A_REGION"), "region"));

        for (Map.Entry<Map<String, String>, String> bad : cases) {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = get(BASE);
            Map<String, String> params = new java.util.HashMap<>(Map.of("accountId", AWS_ID, "region", "ap-south-1"));
            params.putAll(bad.getKey());
            params.forEach(request::param);
            mvc().perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value(bad.getValue()));
        }
        mvc().perform(get(BASE).param("accountId", AWS_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("region"));
        mvc().perform(get("/api/eks/automation/activity/-bad-cluster-" + QUERY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cluster"));

        verifyNoInteractions(accessService, clusterConfigRepository, actionRepository);
    }

    @Test
    @DisplayName("a database fault is 500 K8S_INTERNAL, never an empty page")
    void aDatabaseFaultIsReportedAsOne() throws Exception {
        when(actionRepository.findActivityPage(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mvc().perform(get(BASE + QUERY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("K8S_INTERNAL"))
                .andExpect(jsonPath("$.message").value("XamOps couldn't read its activity log just now."));
    }

    @Test
    @DisplayName("the feed is read-only: the controller maps GET and nothing else")
    void theFeedIsGetOnly() {
        RequestMapping base = AutomationActivityController.class.getAnnotation(RequestMapping.class);
        assertThat(base.method()).isEmpty();
        assertThat(base.value()).containsExactly("/api/eks/automation/activity");
        List<Method> handlers = new ArrayList<>();
        for (Method method : AutomationActivityController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class) || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(RequestMapping.class))
                    .as(method.getName()).isFalse();
            if (method.isAnnotationPresent(GetMapping.class)) {
                handlers.add(method);
            }
        }
        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrder("read", "summary");
        assertThat(handlers).extracting(method -> method.getAnnotation(GetMapping.class).value()[0])
                .containsExactlyInAnyOrder("/{cluster}", "/{cluster}/summary");
    }

    // ---------------------------------------------------------------- the per-day summary (FEAT-005)

    @Test
    @DisplayName("summary: days and feature reach the service for the tenant's cluster; 7 days and every feature by default")
    void theSummaryParametersReachTheService() throws Exception {
        mvc().perform(get(BASE + "/summary" + QUERY + "&days=14&feature=hpa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.engine.enabled").value(true))
                .andExpect(jsonPath("$.days.length()").value(0));
        verify(service).summary(eq(TARGET), eq(EnumSet.of(AutomationActivityService.Feature.HPA)), eq(14));
        verify(actionRepository).findSummaryRows(eq(7L), eq(42L), any(), any(),
                eq(AutomationActivityService.MAX_SUMMARY_ROWS + 1));

        mvc().perform(get(BASE + "/summary" + QUERY)).andExpect(status().isOk());
        verify(service).summary(eq(TARGET), eq(EnumSet.noneOf(AutomationActivityService.Feature.class)), eq(7));
    }

    @Test
    @DisplayName("summary: the wire shape, field by field")
    void theSummaryWireShape() throws Exception {
        Map<String, ActivitySummaryDto.Counts> byFeature = new LinkedHashMap<>();
        byFeature.put("VERTICAL", new ActivitySummaryDto.Counts(1, 2, 3, 0, 5));
        byFeature.put("HPA", new ActivitySummaryDto.Counts(0, 0, 0, 4, 0));
        doReturn(new ActivitySummaryDto(new ActivityPageDto.Engine(false), "Asia/Kolkata", "2026-10-13",
                "2026-10-19", Instant.parse("2026-10-12T05:00:00Z"), Instant.parse("2026-10-14T05:00:00Z"), false,
                List.of(new ActivitySummaryDto.Day("2026-10-14", 1, 2, 3, 4, 5, byFeature))))
                .when(service).summary(any(), any(), anyInt());

        mvc().perform(get(BASE + "/summary" + QUERY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.enabled").value(false))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.from").value("2026-10-13"))
                .andExpect(jsonPath("$.to").value("2026-10-19"))
                .andExpect(jsonPath("$.firstRecordedAt").value("2026-10-12T05:00:00Z"))
                .andExpect(jsonPath("$.completeSince").value("2026-10-14T05:00:00Z"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-14"))
                .andExpect(jsonPath("$.days[0].applied").value(1))
                .andExpect(jsonPath("$.days[0].putBack").value(2))
                .andExpect(jsonPath("$.days[0].refused").value(3))
                .andExpect(jsonPath("$.days[0].testRuns").value(4))
                .andExpect(jsonPath("$.days[0].failed").value(5))
                .andExpect(jsonPath("$.days[0].byFeature.VERTICAL.applied").value(1))
                .andExpect(jsonPath("$.days[0].byFeature.VERTICAL.putBack").value(2))
                .andExpect(jsonPath("$.days[0].byFeature.VERTICAL.refused").value(3))
                .andExpect(jsonPath("$.days[0].byFeature.VERTICAL.failed").value(5))
                .andExpect(jsonPath("$.days[0].byFeature.HPA.testRuns").value(4));
    }

    @Test
    @DisplayName("summary: a malformed days or feature is 400 K8S_INVALID_REQUEST naming it, before any lookup")
    void malformedSummaryParametersAreRefused() throws Exception {
        List<Map.Entry<String, String>> cases = List.of(Map.entry("days", "0"), Map.entry("days", "31"),
                Map.entry("days", "-1"), Map.entry("days", "seven"), Map.entry("days", "007"),
                Map.entry("feature", "NODE"), Map.entry("feature", "HPA,"));
        for (Map.Entry<String, String> bad : cases) {
            mvc().perform(get(BASE + "/summary").param("accountId", AWS_ID).param("region", "ap-south-1")
                            .param(bad.getKey(), bad.getValue()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value(bad.getKey()));
        }
        mvc().perform(get(BASE + "/summary").param("region", "ap-south-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("accountId"));
        mvc().perform(get("/api/eks/automation/activity/-bad-cluster-/summary" + QUERY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cluster"));

        verifyNoInteractions(accessService, clusterConfigRepository, actionRepository);
    }

    @Test
    @DisplayName("summary: another tenant's account is 403 and an unregistered cluster 404, and nothing is read")
    void theSummaryIsTenantScoped() throws Exception {
        mvc().perform(get(BASE + "/summary?accountId=" + FOREIGN_ID + "&region=ap-south-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("K8S_NO_ACCOUNT_ACCESS"));
        mvc().perform(get("/api/eks/automation/activity/elsewhere/summary" + QUERY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));

        verifyNoInteractions(actionRepository);
    }

    @Test
    @DisplayName("summary: a database fault is 500 K8S_INTERNAL, never an empty summary")
    void aSummaryDatabaseFaultIsReportedAsOne() throws Exception {
        when(actionRepository.findFirstAppliedAt(anyLong(), anyLong(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mvc().perform(get(BASE + "/summary" + QUERY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("K8S_INTERNAL"));
    }

    // ---------------------------------------------------------------- fixtures

    private MockMvc mvc() {
        AutomationActivityController controller =
                new AutomationActivityController(accessService, clusterConfigRepository, service);
        // Instants as ISO-8601 text, as the application's own mapper writes them.
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }

    private static WorkloadVerticalAction row(long id, String kind, String namespace, String name, String outcome) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setId(id);
        row.setClientId(7L);
        row.setClusterConfigId(42L);
        row.setNamespace(namespace);
        row.setWorkloadKind(kind);
        row.setWorkloadName(name);
        row.setTriggerSource("MANUAL");
        row.setDryRun(false);
        row.setOutcome(outcome);
        row.setRestartFree(false);
        row.setAppliedAt(Instant.parse("2026-10-13T05:00:00Z").plusSeconds(600 * id));
        return row;
    }

    /** The row UAT holds for prod-sim/throttled-encoder from 2026-10-12, before the activity log. */
    private static WorkloadVerticalAction legacyDeferral(long id) {
        WorkloadVerticalAction row = row(id, "Deployment", "prod-sim", "throttled-encoder", "FAILED");
        row.setBeforeJson("[{\"container\":\"encoder\",\"cpuRequestMillis\":63}]");
        row.setAfterJson("[{\"container\":\"encoder\",\"cpuRequestMillis\":10,\"cpuLimitMillis\":100,"
                + "\"memRequestBytes\":67108864,\"memLimitBytes\":134217728}]");
        row.setDetail("The kubelet deferred the resize of pod prod-sim/throttled-encoder-mcw8q because its node"
                + " has no room for it right now (cpu).");
        return row;
    }

    private static WorkloadVerticalAction hpaChange(long id) {
        WorkloadVerticalAction row = row(id, "HorizontalPodAutoscaler", "team-b", "php-apache", "APPLIED");
        row.setBeforeJson("{\"minReplicas\":1,\"maxReplicas\":5,\"targetUtilizationPct\":50}");
        row.setAfterJson("{\"minReplicas\":1,\"maxReplicas\":8,\"targetUtilizationPct\":50}");
        row.setCorrelationId("corr-" + id);
        return row;
    }

    private static WorkloadVerticalAction putBack(long id) {
        WorkloadVerticalAction row = row(id, "Deployment", "team-a", "overprovisioned-api", "NOT_CHANGED");
        row.setTriggerSource("AUTO");
        row.setCorrelationId("corr-" + id);
        String size = "{\"cpuRequestMillis\":1000,\"cpuLimitMillis\":1000,\"memRequestBytes\":1073741824,"
                + "\"memLimitBytes\":1073741824}";
        row.setBeforeJson("[{\"container\":\"api\",\"cpuRequestMillis\":25,\"cpuLimitMillis\":25}]");
        row.setAfterJson("{\"terminal\":\"DEFERRED\",\"reverted\":true,\"containers\":[{\"container\":\"api\","
                + "\"observedBefore\":" + size + ",\"observedAfter\":" + size + "}],\"pods\":[{\"pod\":"
                + "\"overprovisioned-api-1\",\"node\":null,\"uidChanged\":false,\"restartsBefore\":0,"
                + "\"restartsAfter\":0,\"ready\":true,\"revert\":{\"state\":\"REVERTED\",\"detail\":\"Put back.\"}}]}");
        row.setDetail("Deferred, and put back. Revert: REVERTED.");
        return row;
    }
}
