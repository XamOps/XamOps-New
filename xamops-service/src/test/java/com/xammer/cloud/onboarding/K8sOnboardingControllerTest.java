package com.xammer.cloud.onboarding;

import com.xammer.cloud.controller.K8sOnboardingController;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.ClusterOffboardingTombstoneRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.onboarding.ClusterKeyService;
import com.xammer.cloud.service.onboarding.ConnectTokenService;
import com.xammer.cloud.service.onboarding.OnboardingAuditService;
import com.xammer.cloud.service.onboarding.OnboardingException;
import com.xammer.cloud.service.onboarding.OnboardingFeatureCatalog;
import com.xammer.cloud.service.onboarding.OnboardingPreflightService;
import com.xammer.cloud.service.onboarding.PermissionManifestService;
import com.xammer.cloud.service.onboarding.ScriptCommandRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP-12 (design §4.1/§4.2): validation/authorization and the connect-token response. AC-2: the
 * rendered command carries the short-lived connect token (xct_) and NEVER a long-lived key (xamops_).
 */
class K8sOnboardingControllerTest {

    private K8sOnboardingController controller;
    private K8sAutomationAccessService accessService;
    private KubernetesClusterConfigRepository clusterRepo;
    private ClusterOffboardingTombstoneRepository tombstoneRepo;
    private ConnectTokenService tokenService;
    private ClientUserDetails user;

    @BeforeEach
    void setUp() {
        accessService = mock(K8sAutomationAccessService.class);
        clusterRepo = mock(KubernetesClusterConfigRepository.class);
        tombstoneRepo = mock(ClusterOffboardingTombstoneRepository.class);
        tokenService = mock(ConnectTokenService.class);
        ClusterKeyService keyService = mock(ClusterKeyService.class);
        OnboardingPreflightService preflight = mock(OnboardingPreflightService.class);
        PermissionManifestService manifest = new PermissionManifestService();
        OnboardingFeatureCatalog catalog = new OnboardingFeatureCatalog();
        ScriptCommandRenderer renderer = new ScriptCommandRenderer();
        ReflectionTestUtils.setField(renderer, "serverUrl", "https://live.xamops.test");
        OnboardingAuditService audit = mock(OnboardingAuditService.class);

        controller = new K8sOnboardingController(accessService, clusterRepo, tombstoneRepo, tokenService,
                keyService, preflight, manifest, catalog, renderer, audit);
        ReflectionTestUtils.setField(controller, "onboardingEnabled", true);

        user = mock(ClientUserDetails.class);
        when(user.getId()).thenReturn(10L);
        when(user.getClientId()).thenReturn(1L);

        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setAwsAccountId("111111111111");
        account.setProvider("AWS");
        account.setAccessType("read-write");
        account.setStatus("CONNECTED");
        when(accessService.resolveAccounts(eq(user), eq("111111111111"))).thenReturn(List.of(account));
    }

    @Test
    void invalidClusterName_is400() {
        assertThatThrownBy(() -> controller.status(user, "bad name!", "111111111111"))
                .isInstanceOf(OnboardingException.class)
                .satisfies(e -> assertThat(((OnboardingException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void noAccountAccess_is403() {
        when(accessService.resolveAccounts(eq(user), eq("222222222222"))).thenReturn(List.of());
        assertThatThrownBy(() -> controller.status(user, "prod-eks", "222222222222"))
                .isInstanceOf(OnboardingException.class)
                .satisfies(e -> assertThat(((OnboardingException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void connectToken_commandCarriesTokenNotLongLivedKey_ac2() {
        when(clusterRepo.findByCloudAccountIdAndClusterName(eq(7L), eq("prod-eks"))).thenReturn(Optional.empty());
        when(clusterRepo.save(any())).thenAnswer(inv -> {
            KubernetesClusterConfig c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(100L);
            }
            return c;
        });

        ClusterConnectToken row = new ClusterConnectToken();
        row.setId(1L);
        row.setTokenPrefix("xct_AbCdEfGh");
        row.setClusterConfigId(100L);
        row.setPurpose(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY);
        row.setIssuedAt(java.time.Instant.parse("2026-10-30T12:00:00Z"));
        row.setExpiresAt(java.time.Instant.parse("2026-10-30T12:10:00Z"));
        String rawToken = "xct_" + "A".repeat(43);
        when(tokenService.issue(eq(100L), eq(ConnectTokenService.PURPOSE_CONNECT_READ_ONLY), anyLong(), anyLong()))
                .thenReturn(new ConnectTokenService.IssuedToken(rawToken, row));

        ResponseEntity<Map<String, Object>> resp = controller.connectToken(user, "prod-eks", "111111111111",
                Map.of("region", "ap-south-1", "phase", "READ_ONLY", "createReadRole", true,
                        "features", List.of("COST_INSIGHTS")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String command = (String) resp.getBody().get("command");
        assertThat(command).contains(rawToken);          // short-lived token present
        assertThat(command).doesNotContain("xamops_");    // AC-2: no long-lived key in the command
        assertThat(resp.getBody().get("token")).isEqualTo(rawToken);
    }

    @Test
    void connectToken_automationWithoutPhase2Feature_is400() {
        when(clusterRepo.findByCloudAccountIdAndClusterName(eq(7L), eq("prod-eks")))
                .thenReturn(Optional.of(existingConfig()));
        assertThatThrownBy(() -> controller.connectToken(user, "prod-eks", "111111111111",
                Map.of("region", "ap-south-1", "phase", "AUTOMATION", "features", List.of("COST_INSIGHTS"))))
                .isInstanceOf(OnboardingException.class)
                .satisfies(e -> assertThat(((OnboardingException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void permissions_includesFeaturesViewWithAvailability() {
        ResponseEntity<Map<String, Object>> resp = controller.permissions(user, "111111111111", "prod-eks",
                "ap-south-1", "AUTOMATION", true, "NODE_CONSOLIDATION");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("hash");
        assertThat(resp.getBody()).containsKey("features");
        assertThat(resp.getBody().get("nodeRemoval")).isEqualTo(false); // pfCompute false ⇒ no removal
    }

    @Test
    void disabled_whenFlagOff_is404() {
        ReflectionTestUtils.setField(controller, "onboardingEnabled", false);
        assertThatThrownBy(() -> controller.status(user, "prod-eks", "111111111111"))
                .isInstanceOf(OnboardingException.class)
                .satisfies(e -> assertThat(((OnboardingException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private KubernetesClusterConfig existingConfig() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(100L);
        c.setCloudAccountId(7L);
        c.setClusterName("prod-eks");
        c.setRegion("ap-south-1");
        return c;
    }
}
