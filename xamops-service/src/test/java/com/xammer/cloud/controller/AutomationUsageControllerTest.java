package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.AutomationUsageController;
import com.xammer.cloud.service.automation.workload.api.AutomationUsageService;
import com.xammer.cloud.service.automation.workload.api.UnknownTargetException;
import com.xammer.cloud.service.automation.workload.api.UsageWindow;
import com.xammer.cloud.service.automation.workload.api.dto.SparklinesDto;
import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.usage.PromQl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The usage endpoints' contracts: GET only, tenancy exactly as endpoint 1, every parameter rule as a
 * 400 naming its field before any lookup, the 404s, and the exact JSON field names FEAT-006 reads.
 * The service is mocked, so nothing reaches AWS, a cluster or Prometheus.
 */
class AutomationUsageControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String BASE = "/api/eks/automation/usage/xamops-test-cluster";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";
    private static final String CLUSTER_72H = QUERY + "&scope=cluster&resource=cpu&window=72h";
    private static final String WORKLOAD = QUERY + "&scope=workload&resource=cpu"
            + "&namespace=team-b&kind=Deployment&name=php-apache";

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private AutomationUsageService usageService;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        usageService = mock(AutomationUsageService.class);

        Client client = new Client();
        client.setId(7L);
        account = new CloudAccount();
        account.setId(3L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setStatus("CONNECTED");
        account.setClient(client);

        when(accessService.requireAwsAccess(any(), eq(AWS_ID))).thenReturn(account);
        when(accessService.requireAwsAccess(any(), eq(FOREIGN_ID)))
                .thenThrow(new AccessDeniedException("Account not found or access denied."));
        when(usageService.usage(any(), any())).thenReturn(sample());
        when(usageService.sparklines(any(), any())).thenReturn(sparklines());

        ClientUserDetails principal = new ClientUserDetails("user@example.com", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), 10L, 7L, "tenant-a");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ the happy path and the wire

    @Test
    @DisplayName("a cluster read reaches the service with the canonical target and answers the exact field names")
    void aClusterReadIsTenantScopedAndShaped() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(BASE + CLUSTER_72H))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.scope").value("cluster"))
                .andExpect(jsonPath("$.resource").value("cpu"))
                .andExpect(jsonPath("$.unit").value("cores"))
                .andExpect(jsonPath("$.workloadRef").value(nullValue()))
                .andExpect(jsonPath("$.window").value("72h"))
                .andExpect(jsonPath("$.stepSeconds").value(1800))
                .andExpect(jsonPath("$.start").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.end").value("2026-10-16T17:06:00Z"))
                .andExpect(jsonPath("$.source.type").value("PROMETHEUS_PROXY"))
                .andExpect(jsonPath("$.source.service").value("monitoring/prometheus-server:80"))
                .andExpect(jsonPath("$.series[0].key").value("used"))
                .andExpect(jsonPath("$.series[0].kind").value("used"))
                .andExpect(jsonPath("$.series[0].label").value("Used"))
                .andExpect(jsonPath("$.series[0].query").value("sum(rate(container_cpu_usage_seconds_total[5m]))"))
                .andExpect(jsonPath("$.series[0].points[0][0]").value(1792170000))
                .andExpect(jsonPath("$.series[0].points[0][1]").value(0.25))
                .andExpect(jsonPath("$.series[0].points[1][0]").value(1792171800))
                .andExpect(jsonPath("$.series[0].points[1][1]").value(nullValue()))
                .andExpect(jsonPath("$.namespaces").value(nullValue()))
                .andExpect(jsonPath("$.coverage.firstSampleAt").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.coverage.covered").value(0.5))
                .andExpect(jsonPath("$.unavailable").value(nullValue()));

        ArgumentCaptor<ClusterTarget> target = ArgumentCaptor.forClass(ClusterTarget.class);
        ArgumentCaptor<AutomationUsageService.UsageRequest> request =
                ArgumentCaptor.forClass(AutomationUsageService.UsageRequest.class);
        verify(usageService).usage(target.capture(), request.capture());
        assertThat(target.getValue().clientId()).isEqualTo(7L);
        assertThat(target.getValue().clusterConfigId()).isEqualTo(42L);
        assertThat(target.getValue().clusterName()).isEqualTo("xamops-test-cluster");
        assertThat(target.getValue().region()).isEqualTo("ap-south-1");
        assertThat(request.getValue()).isEqualTo(new AutomationUsageService.UsageRequest(
                AutomationUsageService.Scope.CLUSTER, PromQl.Resource.CPU, UsageWindow.fixed("72h").orElseThrow(),
                null));
    }

    @Test
    @DisplayName("scope=workload carries its namespace, kind and name; scope=namespace takes none")
    void scopesCarryTheirParameters() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(BASE + WORKLOAD + "&window=24h")).andExpect(status().isOk());
        mvc().perform(get(BASE + QUERY + "&scope=namespace&resource=memory&window=168h")).andExpect(status().isOk());

        ArgumentCaptor<AutomationUsageService.UsageRequest> request =
                ArgumentCaptor.forClass(AutomationUsageService.UsageRequest.class);
        verify(usageService, org.mockito.Mockito.times(2)).usage(any(), request.capture());
        assertThat(request.getAllValues().get(0)).isEqualTo(new AutomationUsageService.UsageRequest(
                AutomationUsageService.Scope.WORKLOAD, PromQl.Resource.CPU, UsageWindow.fixed("24h").orElseThrow(),
                WorkloadRef.of("team-b", "Deployment", "php-apache")));
        assertThat(request.getAllValues().get(1)).isEqualTo(new AutomationUsageService.UsageRequest(
                AutomationUsageService.Scope.NAMESPACE, PromQl.Resource.MEMORY,
                UsageWindow.fixed("168h").orElseThrow(), null));
    }

    @Test
    @DisplayName("a custom window reaches the service as asked, to the whole second")
    void aCustomWindowIsPassedThrough() throws Exception {
        givenClusterConfigByPrimaryKey();
        Instant from = Instant.now().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant to = from.plus(6, ChronoUnit.HOURS);

        mvc().perform(get(BASE + WORKLOAD + "&window=custom&from=" + from + "&to=" + to + "&step=60"))
                .andExpect(status().isOk());

        ArgumentCaptor<AutomationUsageService.UsageRequest> request =
                ArgumentCaptor.forClass(AutomationUsageService.UsageRequest.class);
        verify(usageService).usage(any(), request.capture());
        assertThat(request.getValue().window()).isEqualTo(UsageWindow.custom(from, to, 60));
    }

    @Test
    @DisplayName("the sparklines read 72h only, and answer their exact field names")
    void sparklinesAreShaped() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(BASE + "/sparklines" + QUERY + "&resource=memory&window=72h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.resource").value("memory"))
                .andExpect(jsonPath("$.unit").value("bytes"))
                .andExpect(jsonPath("$.window").value("72h"))
                .andExpect(jsonPath("$.stepSeconds").value(3600))
                .andExpect(jsonPath("$.source.type").value("PROMETHEUS_PROXY"))
                .andExpect(jsonPath("$.queries.used").value("u"))
                .andExpect(jsonPath("$.queries.request").value("r"))
                .andExpect(jsonPath("$.workloads[0].ref.namespace").value("team-b"))
                .andExpect(jsonPath("$.workloads[0].ref.kind").value("Deployment"))
                .andExpect(jsonPath("$.workloads[0].ref.name").value("php-apache"))
                .andExpect(jsonPath("$.workloads[0].used[0][1]").value(13971456.0))
                .andExpect(jsonPath("$.workloads[0].request[0][1]").value(nullValue()))
                .andExpect(jsonPath("$.unavailable").value(nullValue()));
        verify(usageService).sparklines(any(), eq(PromQl.Resource.MEMORY));

        mvc().perform(get(BASE + "/sparklines" + QUERY + "&resource=cpu&window=24h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("window"));
        mvc().perform(get(BASE + "/sparklines" + QUERY + "&window=72h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("resource"));
    }

    // ------------------------------------------------------------------ parameter rules

    @Test
    @DisplayName("scope, resource and window are exact, case-sensitive and required")
    void enumsAreExact() throws Exception {
        expectInvalid(QUERY + "&scope=Cluster&resource=cpu&window=72h", "scope");
        expectInvalid(QUERY + "&scope=workloads&resource=cpu&window=72h", "scope");
        expectInvalid(QUERY + "&scope=cluster&resource=CPU&window=72h", "resource");
        expectInvalid(QUERY + "&scope=cluster&resource=cpu&window=3d", "window");
        expectInvalid(QUERY + "&resource=cpu&window=72h", "scope");
        expectInvalid(QUERY + "&scope=cluster&window=72h", "resource");
        expectInvalid(QUERY + "&scope=cluster&resource=cpu", "window");
        expectInvalid("?region=ap-south-1&scope=cluster&resource=cpu&window=72h", "accountId");
        expectInvalid("?accountId=not-a-number&region=ap-south-1&scope=cluster&resource=cpu&window=72h", "accountId");
        expectInvalid("?accountId=" + AWS_ID + "&region=NOT_A_REGION&scope=cluster&resource=cpu&window=72h", "region");

        verifyNoInteractions(clusterConfigRepository, usageService);
    }

    @Test
    @DisplayName("namespace, kind and name are required with scope=workload and rejected with the other scopes")
    void workloadParametersFollowTheScope() throws Exception {
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&kind=Deployment&name=php-apache", "namespace");
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&namespace=team-b&name=php-apache", "kind");
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&namespace=team-b&kind=Deployment", "name");
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&namespace=Team_B&kind=Deployment"
                + "&name=php-apache", "namespace");
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&namespace=team-b&kind=Job"
                + "&name=php-apache", "kind");
        expectInvalid(QUERY + "&scope=workload&resource=cpu&window=72h&namespace=team-b&kind=Deployment"
                + "&name=-php-", "name");
        expectInvalid(CLUSTER_72H + "&namespace=team-b", "namespace");
        expectInvalid(QUERY + "&scope=namespace&resource=cpu&window=72h&name=php-apache", "name");
        expectInvalid(QUERY + "&scope=namespace&resource=cpu&window=72h&kind=Deployment", "kind");

        verifyNoInteractions(clusterConfigRepository, usageService);
    }

    @Test
    @DisplayName("a custom window: workload only, all three of from/to/step, at most 7 days and 600 points, not in the future")
    void customWindowRules() throws Exception {
        Instant to = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(6, ChronoUnit.HOURS);
        String span = "&from=" + from + "&to=" + to;

        expectInvalid(CLUSTER_72H.replace("72h", "custom") + span + "&step=60", "window");
        expectInvalid(WORKLOAD + "&window=72h" + span + "&step=60", "from");
        expectInvalid(WORKLOAD + "&window=72h&step=60", "step");
        expectInvalid(WORKLOAD + "&window=custom&to=" + to + "&step=60", "from");
        expectInvalid(WORKLOAD + "&window=custom&from=" + from + "&step=60", "to");
        expectInvalid(WORKLOAD + "&window=custom" + span, "step");
        expectInvalid(WORKLOAD + "&window=custom&from=yesterday&to=" + to + "&step=60", "from");
        expectInvalid(WORKLOAD + "&window=custom&from=" + to + "&to=" + from + "&step=60", "from");
        expectInvalid(WORKLOAD + "&window=custom&from=" + to.minus(8, ChronoUnit.DAYS) + "&to=" + to
                + "&step=3600", "from");
        expectInvalid(WORKLOAD + "&window=custom&from=" + from + "&to=" + Instant.now().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS) + "&step=3600", "to");
        expectInvalid(WORKLOAD + "&window=custom" + span + "&step=120", "step");
        expectInvalid(WORKLOAD + "&window=custom" + span + "&step=sixty", "step");
        // 7 days at 60 s would be 10,081 points per series: the cap is 600.
        expectInvalid(WORKLOAD + "&window=custom&from=" + to.minus(7, ChronoUnit.DAYS) + "&to=" + to
                + "&step=60", "step");

        verifyNoInteractions(clusterConfigRepository, usageService);
    }

    @Test
    @DisplayName("a too-long custom window at too fine a step says how many points it would be")
    void thePointCapExplainsItself() throws Exception {
        Instant to = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        mvc().perform(get(BASE + WORKLOAD + "&window=custom&from=" + to.minus(1, ChronoUnit.DAYS) + "&to=" + to
                        + "&step=60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("step"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1441 points")));
    }

    // ------------------------------------------------------------------ tenancy and 404s

    @Test
    @DisplayName("another tenant's account is refused, and no cluster lookup or read happens")
    void anotherTenantIsRefused() throws Exception {
        mvc().perform(get(BASE + "?accountId=" + FOREIGN_ID + "&region=ap-south-1&scope=cluster&resource=cpu"
                        + "&window=72h"))
                .andExpect(status().isForbidden());
        mvc().perform(get(BASE + "/sparklines?accountId=" + FOREIGN_ID + "&region=ap-south-1&resource=cpu"
                        + "&window=72h"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(clusterConfigRepository, usageService);
    }

    @Test
    @DisplayName("an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED; a legacy row by AWS number resolves")
    void registrationIsRequired() throws Exception {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any())).thenReturn(Optional.empty());

        mvc().perform(get(BASE + CLUSTER_72H))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));
        verifyNoInteractions(usageService);

        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(102227938259L, "xamops-test-cluster"))
                .thenReturn(Optional.of(clusterConfig()));
        mvc().perform(get(BASE + CLUSTER_72H)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a workload the cluster does not have is 404 K8S_INVALID_REQUEST naming the field")
    void anUnknownWorkloadIsNotFound() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(usageService.usage(any(), any())).thenThrow(new UnknownTargetException("name",
                "This cluster has no Deployment team-b/php-apache that XamOps analyses."));

        mvc().perform(get(BASE + WORKLOAD + "&window=72h"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("name"));
    }

    @Test
    @DisplayName("GET only: no handler on this controller can change anything")
    void everyHandlerIsAGet() {
        List<Method> handlers = Arrays.stream(AutomationUsageController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class)
                        || method.isAnnotationPresent(RequestMapping.class))
                .toList();

        assertThat(handlers).hasSize(2).allSatisfy(method -> {
            assertThat(method.isAnnotationPresent(GetMapping.class)).as(method.getName()).isTrue();
            assertThat(method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class) || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(RequestMapping.class)).as(method.getName()).isFalse();
        });
    }

    // ------------------------------------------------------------------ helpers

    private void expectInvalid(String query, String field) throws Exception {
        mvc().perform(get(BASE + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value(field));
    }

    private void givenClusterConfigByPrimaryKey() {
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(3L, "xamops-test-cluster"))
                .thenReturn(Optional.of(clusterConfig()));
    }

    private static KubernetesClusterConfig clusterConfig() {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-cluster");
        config.setRegion("ap-south-1");
        return config;
    }

    private static UsageSeriesDto sample() {
        return new UsageSeriesDto(1, "cluster", "cpu", "cores", null, "72h", 1800,
                Instant.parse("2026-10-13T17:06:00Z"), Instant.parse("2026-10-16T17:06:00Z"),
                UsageSeriesDto.Source.prometheusProxy("monitoring/prometheus-server:80"),
                List.of(new UsageSeriesDto.Series("used", "used", "Used", List.of(
                        new UsageSeriesDto.Point(1792170000L, 0.25), new UsageSeriesDto.Point(1792171800L, null)),
                        "sum(rate(container_cpu_usage_seconds_total[5m]))")),
                null, new UsageSeriesDto.Coverage(Instant.parse("2026-10-13T17:06:00Z"), 0.5), null);
    }

    private static SparklinesDto sparklines() {
        return new SparklinesDto(1, "memory", "bytes", "72h", 3600, Instant.parse("2026-10-13T17:06:00Z"),
                Instant.parse("2026-10-16T17:06:00Z"),
                UsageSeriesDto.Source.prometheusProxy("monitoring/prometheus-server:80"),
                new SparklinesDto.Queries("u", "r"),
                List.of(new SparklinesDto.WorkloadSparkline(new UsageSeriesDto.Ref("team-b", "Deployment", "php-apache"),
                        List.of(new UsageSeriesDto.Point(1792170000L, 13971456.0)),
                        List.of(new UsageSeriesDto.Point(1792170000L, null)))),
                null);
    }

    /**
     * Instants as ISO-8601 strings, as production writes them
     * ({@code spring.jackson2.serialization.write-dates-as-timestamps=false}).
     */
    private MockMvc mvc() {
        AutomationUsageController controller =
                new AutomationUsageController(accessService, clusterConfigRepository, usageService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }
}
