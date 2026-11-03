package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.ClusterIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-10.1 lockdown (design §4.4, AC-18): every {@code /api/v1/clusters/{id}/…} endpoint is
 * tenant-checked. A caller with no entitlement — or no authenticated principal — gets 403 with
 * the same opaque body used for a missing config, so the endpoint is not an enumeration oracle,
 * and the underlying service is never invoked. The retired {@code /agent-install} returns 410.
 */
class ClusterIntegrationControllerLockdownTest {

    private ClusterIntegrationController controller;
    private ClusterIntegrationService service;
    private KubernetesClusterConfigRepository clusterConfigRepository;
    private K8sAutomationAccessService accessService;
    private CloudAccountAccessService cloudAccountAccessService;
    private ClientUserDetails user;

    @BeforeEach
    void setUp() {
        service = mock(ClusterIntegrationService.class);
        clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
        accessService = mock(K8sAutomationAccessService.class);
        cloudAccountAccessService = mock(CloudAccountAccessService.class);
        user = mock(ClientUserDetails.class);
        when(user.getId()).thenReturn(42L);

        controller = new ClusterIntegrationController(
                service, clusterConfigRepository, accessService, cloudAccountAccessService);

        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setCloudAccountId(7L);
        config.setClusterName("prod-eks");
        when(clusterConfigRepository.findById(100L)).thenReturn(Optional.of(config));

        CloudAccount owner = new CloudAccount();
        owner.setId(7L);
        when(accessService.findOwningAccount(config)).thenReturn(Optional.of(owner));
    }

    @Test
    void generateApiKey_crossTenant_is403_andServiceNotCalled() {
        when(cloudAccountAccessService.canAccess(eq(user), any())).thenReturn(false);
        ResponseEntity<Map<String, Object>> r = controller.generateApiKey(user, 100L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).generateApiKey(any(Long.class));
    }

    @Test
    void generateApiKey_unauthenticated_is403() {
        ResponseEntity<Map<String, Object>> r = controller.generateApiKey(null, 100L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).generateApiKey(any(Long.class));
    }

    @Test
    void integrationStatus_missingConfig_is403_sameBodyAsNotYours() {
        when(clusterConfigRepository.findById(999L)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> r = controller.getIntegrationStatus(user, 999L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void owningUser_isAllowedThrough() {
        when(cloudAccountAccessService.canAccess(eq(user), any())).thenReturn(true);
        when(service.getIntegrationStatus(100L)).thenReturn(Map.of("success", true));
        ResponseEntity<Map<String, Object>> r = controller.getIntegrationStatus(user, 100L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void agentInstall_isRetired_410_forOwningUser() {
        when(cloudAccountAccessService.canAccess(eq(user), any())).thenReturn(true);
        ResponseEntity<Map<String, Object>> r = controller.getAgentInstallCommand(user, 100L, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(r.getBody()).containsEntry("error", "GONE");
    }
}
