package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.HpaApplyService;
import com.xammer.cloud.service.automation.workload.api.HpaAutomationController;
import com.xammer.cloud.service.automation.workload.api.HpaHistoryService;
import com.xammer.cloud.service.automation.workload.api.UnknownTargetException;
import com.xammer.cloud.service.automation.workload.api.UsageWindow;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoriesDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoryDto;
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
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
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
 * The HPA history endpoint's contract: GET, tenancy exactly as the apply endpoint, the window enum,
 * the 404 for an HPA nobody knows, and the exact JSON field names FEAT-006 reads.
 */
class HpaHistoryControllerTest {

    private static final String AWS_ID = "102227938259";
    private static final String FOREIGN_ID = "999999999999";
    private static final String HISTORY = "/api/eks/automation/hpa/xamops-test-cluster/team-b/php-apache/history";
    private static final String QUERY = "?accountId=" + AWS_ID + "&region=ap-south-1";

    private CloudAccountAccessService accessService;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private HpaApplyService applyService;
    private HpaHistoryService historyService;

    @BeforeEach
    void setUp() {
        accessService = mock(CloudAccountAccessService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        applyService = mock(HpaApplyService.class);
        historyService = mock(HpaHistoryService.class);

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
        when(historyService.history(any(), any(), any(), any())).thenReturn(sample());

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
    @DisplayName("a history read reaches the service with the canonical target and answers the exact field names")
    void aHistoryReadIsTenantScopedAndShaped() throws Exception {
        givenClusterConfigByPrimaryKey();

        mvc().perform(get(HISTORY + QUERY + "&window=72h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.namespace").value("team-b"))
                .andExpect(jsonPath("$.name").value("php-apache"))
                .andExpect(jsonPath("$.window").value("72h"))
                .andExpect(jsonPath("$.stepSeconds").value(1800))
                .andExpect(jsonPath("$.start").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.end").value("2026-10-16T17:06:00Z"))
                .andExpect(jsonPath("$.source.type").value("PROMETHEUS_PROXY"))
                .andExpect(jsonPath("$.series[0].key").value("currentReplicas"))
                .andExpect(jsonPath("$.series[0].label").value("Replicas running"))
                .andExpect(jsonPath("$.series[0].unit").value("replicas"))
                .andExpect(jsonPath("$.series[0].points[0][0]").value(1791911160))
                .andExpect(jsonPath("$.series[0].points[0][1]").value(5.0))
                .andExpect(jsonPath("$.series[0].points[1][1]").value(nullValue()))
                .andExpect(jsonPath("$.series[0].query").value("max(kube_horizontalpodautoscaler_status_current_replicas)"))
                .andExpect(jsonPath("$.atMax.share").value(0.9993))
                .andExpect(jsonPath("$.atMax.minutesAtMax").value(4317))
                .andExpect(jsonPath("$.atMax.minutesMeasured").value(4320))
                .andExpect(jsonPath("$.atMax.runs[0].from").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.atMax.runs[0].to").value(nullValue()))
                .andExpect(jsonPath("$.atMax.runsTruncated").value(false))
                .andExpect(jsonPath("$.atMax.since").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.atMax.stepSeconds").value(60))
                .andExpect(jsonPath("$.atMax.query").value("q"))
                .andExpect(jsonPath("$.changes[0].at").value("2026-10-14T10:00:00Z"))
                .andExpect(jsonPath("$.changes[0].from").value(5))
                .andExpect(jsonPath("$.changes[0].to").value(8))
                .andExpect(jsonPath("$.changesTruncated").value(false))
                .andExpect(jsonPath("$.firstSampleAt").value("2026-10-13T17:06:00Z"))
                .andExpect(jsonPath("$.unavailable").value(nullValue()));

        ArgumentCaptor<ClusterTarget> target = ArgumentCaptor.forClass(ClusterTarget.class);
        verify(historyService).history(target.capture(), eq("team-b"), eq("php-apache"),
                eq(UsageWindow.fixed("72h").orElseThrow()));
        assertThat(target.getValue().clientId()).isEqualTo(7L);
        assertThat(target.getValue().clusterConfigId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("window is required and exact: 24h, 72h or 168h, never custom")
    void theWindowIsAnExactEnum() throws Exception {
        for (String window : List.of("3d", "72H", "custom", "")) {
            mvc().perform(get(HISTORY + QUERY + "&window=" + window))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value("window"));
        }
        mvc().perform(get(HISTORY + QUERY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("window"));

        verifyNoInteractions(clusterConfigRepository, historyService);
    }

    @Test
    @DisplayName("a malformed namespace or HPA name is 400 before any lookup")
    void malformedIdentifiersAreRejectedFirst() throws Exception {
        mvc().perform(get("/api/eks/automation/hpa/xamops-test-cluster/Team_B/php-apache/history" + QUERY
                        + "&window=72h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("namespace"));
        mvc().perform(get("/api/eks/automation/hpa/xamops-test-cluster/team-b/PHP/history" + QUERY
                        + "&window=72h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("name"));

        verifyNoInteractions(clusterConfigRepository, historyService);
    }

    @Test
    @DisplayName("another tenant is refused with no lookup; an unregistered cluster is 404 K8S_CLUSTER_NOT_REGISTERED")
    void tenancyIsEnforced() throws Exception {
        mvc().perform(get(HISTORY + "?accountId=" + FOREIGN_ID + "&region=ap-south-1&window=72h"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(clusterConfigRepository, historyService);

        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any())).thenReturn(Optional.empty());
        mvc().perform(get(HISTORY + QUERY + "&window=72h"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));
        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("an HPA neither Prometheus nor the cluster knows is 404 K8S_INVALID_REQUEST naming name")
    void anUnknownHpaIsNotFound() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(historyService.history(any(), any(), any(), any())).thenThrow(new UnknownTargetException("name",
                "This cluster has no HPA team-b/php-apache, and Prometheus holds no history for one."));

        mvc().perform(get(HISTORY + QUERY + "&window=72h"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("name"));
    }

    @Test
    @DisplayName("the history handler is a GET")
    void theHistoryHandlerIsAGet() throws Exception {
        assertThat(HpaAutomationController.class.getMethod("history", ClientUserDetails.class, String.class,
                String.class, String.class, String.class, String.class, String.class)
                .isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(HpaAutomationController.class.getMethod("histories", ClientUserDetails.class, String.class,
                String.class, String.class, String.class, String.class)
                .isAnnotationPresent(GetMapping.class)).isTrue();
    }

    // ------------------------------------------------------------------ the HPA list's one read

    private static final String BATCH = "/api/eks/automation/hpa/xamops-test-cluster/history";

    @Test
    @DisplayName("the list's read names its HPAs once each, in order, and answers every entry and the missing ones")
    void aBatchReadIsTenantScopedAndShaped() throws Exception {
        givenClusterConfigByPrimaryKey();
        when(historyService.histories(any(), any(), any())).thenReturn(new HpaHistoriesDto(1, "72h",
                List.of(sample()), List.of(new HpaHistoriesDto.HpaRef("team-c", "gone"))));

        mvc().perform(get(BATCH + QUERY + "&window=72h&hpas=team-b/php-apache,team-c/gone,team-b/php-apache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.window").value("72h"))
                .andExpect(jsonPath("$.histories[0].namespace").value("team-b"))
                .andExpect(jsonPath("$.histories[0].name").value("php-apache"))
                .andExpect(jsonPath("$.histories[0].series[0].key").value("currentReplicas"))
                .andExpect(jsonPath("$.histories[0].atMax.share").value(0.9993))
                .andExpect(jsonPath("$.missing[0].namespace").value("team-c"))
                .andExpect(jsonPath("$.missing[0].name").value("gone"));

        ArgumentCaptor<ClusterTarget> target = ArgumentCaptor.forClass(ClusterTarget.class);
        verify(historyService).histories(target.capture(), eq(List.of(
                        new PromQl.HpaSelector("team-b", "php-apache"), new PromQl.HpaSelector("team-c", "gone"))),
                eq(UsageWindow.fixed("72h").orElseThrow()));
        assertThat(target.getValue().clientId()).isEqualTo(7L);
        assertThat(target.getValue().clusterConfigId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("the list's hpas is required and exact: namespace/name pairs, at most 20, else 400 before any lookup")
    void theBatchHpasAreValidatedFirst() throws Exception {
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i <= 20; i++) {
            tooMany.append(i == 0 ? "" : ",").append("team-b/hpa-").append(i);
        }
        for (String hpas : List.of("", "team-b", "/php-apache", "team-b/", "team-b/php/apache", "Team_B/php-apache",
                "team-b/PHP", "team-b/php-apache,", tooMany.toString())) {
            mvc().perform(get(BATCH + QUERY + "&window=72h&hpas=" + hpas))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("K8S_INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value("hpas"));
        }
        mvc().perform(get(BATCH + QUERY + "&window=72h"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("hpas"));
        mvc().perform(get(BATCH + QUERY + "&window=6h&hpas=team-b/php-apache"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("window"));

        verifyNoInteractions(clusterConfigRepository, historyService);
    }

    @Test
    @DisplayName("the list's read is refused for another tenant with no lookup, and 404 for an unregistered cluster")
    void theBatchTenancyIsEnforced() throws Exception {
        mvc().perform(get(BATCH + "?accountId=" + FOREIGN_ID + "&region=ap-south-1&window=72h&hpas=team-b/php-apache"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(clusterConfigRepository, historyService);

        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(any(), any())).thenReturn(Optional.empty());
        mvc().perform(get(BATCH + QUERY + "&window=72h&hpas=team-b/php-apache"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("K8S_CLUSTER_NOT_REGISTERED"));
        verifyNoInteractions(historyService);
    }

    // ------------------------------------------------------------------ helpers

    private void givenClusterConfigByPrimaryKey() {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-cluster");
        config.setRegion("ap-south-1");
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(3L, "xamops-test-cluster"))
                .thenReturn(Optional.of(config));
    }

    private static HpaHistoryDto sample() {
        Instant start = Instant.parse("2026-10-13T17:06:00Z");
        return new HpaHistoryDto(1, "team-b", "php-apache", "72h", 1800, start,
                Instant.parse("2026-10-16T17:06:00Z"),
                UsageSeriesDto.Source.prometheusProxy("monitoring/prometheus-server:80"),
                List.of(new HpaHistoryDto.Series("currentReplicas", "Replicas running", "replicas", List.of(
                        new UsageSeriesDto.Point(1791911160L, 5.0), new UsageSeriesDto.Point(1791912960L, null)),
                        "max(kube_horizontalpodautoscaler_status_current_replicas)")),
                new HpaHistoryDto.AtMax(0.9993, 4317, 4320, List.of(new HpaHistoryDto.Run(start, null)), false,
                        start, 60, "q"),
                List.of(new HpaHistoryDto.Change(Instant.parse("2026-10-14T10:00:00Z"), 5, 8)), false, start, null);
    }

    private MockMvc mvc() {
        HpaAutomationController controller = new HpaAutomationController(
                accessService, clusterConfigRepository, applyService, historyService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }
}
