package com.xammer.cloud.onboarding;

import com.xammer.cloud.controller.CloudK8sController;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.service.EksClusterUsageService;
import com.xammer.cloud.service.EksService;
import com.xammer.cloud.service.onboarding.CloudNodegroupsReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP-16 (design §8.1): the nodegroup-scaling endpoint tenant-checks via resolveAccounts (403 when
 * not owned) and returns source=AGENT with rows when a fresh row exists, else source=NONE.
 */
class NodegroupScalingEndpointTest {

    private CloudK8sController controller;
    private K8sAutomationAccessService accessService;
    private KubernetesClusterConfigRepository clusterRepo;
    private CloudNodegroupsReader reader;
    private ClientUserDetails user;

    @BeforeEach
    void setUp() {
        accessService = mock(K8sAutomationAccessService.class);
        clusterRepo = mock(KubernetesClusterConfigRepository.class);
        reader = mock(CloudNodegroupsReader.class);
        controller = new CloudK8sController(mock(EksService.class), mock(EksClusterUsageService.class),
                accessService, clusterRepo, reader);
        user = mock(ClientUserDetails.class);
    }

    @Test
    void crossTenant_is403() {
        when(accessService.resolveAccounts(eq(user), eq("222222222222"))).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> r = controller.nodegroupScaling(user, "prod-eks", "222222222222");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidClusterName_is400() {
        ResponseEntity<Map<String, Object>> r = controller.nodegroupScaling(user, "bad name!", "111111111111");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void freshRow_returnsAgentSource() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        when(accessService.resolveAccounts(eq(user), eq("111111111111"))).thenReturn(List.of(a));
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        when(clusterRepo.findByCloudAccountIdAndClusterName(eq(7L), eq("prod-eks"))).thenReturn(Optional.of(config));
        when(reader.read(any(), any(Duration.class))).thenReturn(Optional.of(
                new CloudNodegroupsReader.Result("2026-10-30T12:00:00Z",
                        List.of(new CloudNodegroupsReader.Nodegroup("ng-1", "ACTIVE", "ON_DEMAND",
                                1, 4, 4, List.of("eks-ng-1"), "AL2023", List.of("m5.large"))))));

        ResponseEntity<Map<String, Object>> r = controller.nodegroupScaling(user, "prod-eks", "111111111111");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("source", "AGENT");
    }

    @Test
    void noFreshRow_returnsNoneSource() {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        when(accessService.resolveAccounts(eq(user), eq("111111111111"))).thenReturn(List.of(a));
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(100L);
        when(clusterRepo.findByCloudAccountIdAndClusterName(eq(7L), eq("prod-eks"))).thenReturn(Optional.of(config));
        when(reader.read(any(), any(Duration.class))).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> r = controller.nodegroupScaling(user, "prod-eks", "111111111111");
        assertThat(r.getBody()).containsEntry("source", "NONE");
    }
}
