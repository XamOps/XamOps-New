package com.xammer.cloud.service.automation.workload.access;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.EksTokenGenerator;
import com.xammer.cloud.service.K8sClientFactory;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.EksException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The providers' only contract with the rest of the system: every way of failing to open a cluster
 * comes out as one of two typed kinds, and nothing else escapes.
 *
 * <p>This matters because the UI's gate rendering depends on telling "add an EKS access entry" from
 * "this is a private endpoint". A leaked {@code RuntimeException} would reach the user as
 * "something went wrong" with no remedy. Every AWS and Kubernetes collaborator is mocked:
 * <strong>no AWS call and no cluster contact occurs in this test.</strong>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterAccessFailureMappingTest {

    private static final Long CLIENT_ID = 7L;
    private static final String AWS_ACCOUNT = "102227938259";

    @Mock
    CloudAccountRepository cloudAccountRepository;
    @Mock
    AwsClientProvider awsClientProvider;
    @Mock
    EksTokenGenerator eksTokenGenerator;
    @Mock
    K8sClientFactory k8sClientFactory;
    @Mock
    KubernetesClusterConfigRepository clusterConfigRepository;
    @Mock
    EksClient eksClient;

    @InjectMocks
    EksClusterAccessProvider eksProvider;

    private final ClusterTarget target =
            new ClusterTarget(CLIENT_ID, 42L, AWS_ACCOUNT, "xamops-test-cluster", "ap-south-1");

    // ------------------------------------------------------------------ configuration failures

    @Test
    void unknownAwsAccountIsNotConfigured() {
        when(cloudAccountRepository.findByAwsAccountId(anyString())).thenReturn(List.of());

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void anotherTenantsAccountIsNotConfiguredAndNotAnOracle() {
        // The account exists and matches the AWS number, but belongs to a different client.
        when(cloudAccountRepository.findByAwsAccountId(anyString()))
                .thenReturn(List.of(awsAccount(AWS_ACCOUNT, 999L)));

        ClusterAccessException failure = assertThrows(ClusterAccessException.class,
                () -> eksProvider.open(target));

        assertThat(failure.kind()).isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
        // Same wording as the unknown-account case: a distinguishing message would reveal which
        // AWS accounts are registered on the platform.
        assertThat(failure).hasMessageContaining("not connected to XamOps for this workspace");
    }

    @Test
    void missingAwsAccountOrRegionIsNotConfigured() {
        ClusterTarget noRegion = new ClusterTarget(CLIENT_ID, 42L, AWS_ACCOUNT, "c", "  ");
        assertThat(eksProvider.supports(noRegion)).isFalse();
        assertThat(kindOf(() -> eksProvider.open(noRegion)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void describeClusterAccessDeniedIsNotConfigured() {
        givenEntitledAccount();
        when(eksClient.describeCluster(any(java.util.function.Consumer.class)))
                .thenThrow(EksException.builder()
                        .message("User is not authorized to perform eks:DescribeCluster")
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException").build())
                        .statusCode(403)
                        .build());

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void clusterNotFoundIsNotConfigured() {
        givenEntitledAccount();
        when(eksClient.describeCluster(any(java.util.function.Consumer.class)))
                .thenThrow(EksException.builder()
                        .message("No cluster found for name: xamops-test-cluster")
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("ResourceNotFoundException").build())
                        .statusCode(404)
                        .build());

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void apiServerRejectingTheTokenIsNotConfigured() {
        givenDescribedCluster();
        when(k8sClientFactory.createFromComponents(anyString(), anyString(), anyString()))
                .thenThrow(new KubernetesClientException("Forbidden", 403, null));

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void aClusterWithNoVerifiableCaIsNotConfiguredRatherThanTrustedBlindly() {
        // The B7 fix raises TlsTrustException instead of connecting insecurely. That is a
        // configuration problem, so it must not be reported as a network one.
        givenDescribedCluster();
        when(k8sClientFactory.createFromComponents(anyString(), any(), anyString()))
                .thenThrow(new K8sClientFactory.TlsTrustException("No cluster CA certificate was supplied"));

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    // ------------------------------------------------------------------ reachability failures

    @Test
    void connectTimeoutIsUnreachable() {
        givenEntitledAccount();
        when(eksClient.describeCluster(any(java.util.function.Consumer.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: connect timed out",
                        new SocketTimeoutException("connect timed out")));

        assertThat(kindOf(() -> eksProvider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_UNREACHABLE);
    }

    @Test
    void privateEndpointTimeoutOnTheApiServerIsUnreachable() {
        givenDescribedCluster();
        when(k8sClientFactory.createFromComponents(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("operation timed out",
                        new SocketTimeoutException("connect timed out")));

        ClusterAccessException failure = assertThrows(ClusterAccessException.class,
                () -> eksProvider.open(target));

        assertThat(failure.kind()).isEqualTo(ClusterAccessException.Kind.CLUSTER_UNREACHABLE);
        assertThat(failure).hasMessageContaining("private endpoint");
    }

    // ------------------------------------------------------------------ nothing else escapes

    @Test
    void anUnclassifiedRuntimeExceptionStillComesOutTyped() {
        givenEntitledAccount();
        when(eksClient.describeCluster(any(java.util.function.Consumer.class)))
                .thenThrow(new IllegalStateException("something nobody anticipated"));

        // Unclassified defaults to UNREACHABLE: the only honest claim about a failure we cannot
        // identify is that the connection was not established. It must never be a raw exception.
        ClusterAccessException failure = assertThrows(ClusterAccessException.class,
                () -> eksProvider.open(target));
        assertThat(failure.kind()).isEqualTo(ClusterAccessException.Kind.CLUSTER_UNREACHABLE);
        assertThat(failure.reasonCode()).isEqualTo("CLUSTER_UNREACHABLE");
    }

    @Test
    void everyKindMapsOneToOneOntoASharedReasonCode() {
        for (ClusterAccessException.Kind kind : ClusterAccessException.Kind.values()) {
            assertThat(new ClusterAccessException(kind, "x").reasonCode()).isEqualTo(kind.name());
        }
        assertThat(ClusterAccessException.Kind.values()).hasSize(2);
    }

    // ------------------------------------------------------------------ kubeconfig provider

    @Test
    void noStoredKubeconfigIsNotConfigured() {
        KubeconfigClusterAccessProvider provider =
                new KubeconfigClusterAccessProvider(clusterConfigRepository, k8sClientFactory);
        when(clusterConfigRepository.findById(42L)).thenReturn(Optional.of(new KubernetesClusterConfig()));

        ClusterAccessException failure = assertThrows(ClusterAccessException.class, () -> provider.open(target));

        assertThat(failure.kind()).isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
        assertThat(failure).hasMessageContaining("No kubeconfig is stored");
    }

    @Test
    void unregisteredClusterConfigIsNotConfigured() {
        KubeconfigClusterAccessProvider provider =
                new KubeconfigClusterAccessProvider(clusterConfigRepository, k8sClientFactory);
        when(clusterConfigRepository.findById(42L)).thenReturn(Optional.empty());

        assertThat(kindOf(() -> provider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void kubeconfigWithNoCaIsNotConfigured() {
        KubeconfigClusterAccessProvider provider =
                new KubeconfigClusterAccessProvider(clusterConfigRepository, k8sClientFactory);
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setKubeconfigYaml("apiVersion: v1\nkind: Config\n");
        when(clusterConfigRepository.findById(42L)).thenReturn(Optional.of(config));
        when(k8sClientFactory.createFromKubeconfig(anyString()))
                .thenThrow(new K8sClientFactory.TlsTrustException("Kubeconfig declares no cluster CA"));

        assertThat(kindOf(() -> provider.open(target)))
                .isEqualTo(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
    }

    @Test
    void bothProvidersReportAStableName() {
        KubeconfigClusterAccessProvider provider =
                new KubeconfigClusterAccessProvider(clusterConfigRepository, k8sClientFactory);
        assertThat(eksProvider.providerName()).isEqualTo("eks-direct");
        assertThat(provider.providerName()).isEqualTo("kubeconfig");
        assertThat(provider.supports(target)).isTrue();
        assertThat(eksProvider.supports(target)).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private void givenEntitledAccount() {
        when(cloudAccountRepository.findByAwsAccountId(anyString()))
                .thenReturn(List.of(awsAccount(AWS_ACCOUNT, CLIENT_ID)));
        when(awsClientProvider.getEksClient(any(), anyString())).thenReturn(eksClient);
    }

    /** An account that resolves and a cluster that describes, so the failure is at the API server. */
    private void givenDescribedCluster() {
        givenEntitledAccount();
        when(eksClient.describeCluster(any(java.util.function.Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.eks.model.DescribeClusterResponse.builder()
                        .cluster(software.amazon.awssdk.services.eks.model.Cluster.builder()
                                .name("xamops-test-cluster")
                                .endpoint("https://example.eks.amazonaws.com")
                                .certificateAuthority(c -> c.data("Zm9v"))
                                .build())
                        .build());
        when(eksTokenGenerator.generateTokenForCluster(anyString(), anyString(), anyString()))
                .thenReturn("k8s-aws-v1.token");
    }

    private static CloudAccount awsAccount(String awsAccountId, Long clientId) {
        Client client = new Client();
        client.setId(clientId);
        CloudAccount account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(awsAccountId);
        account.setProvider("AWS");
        account.setClient(client);
        return account;
    }

    private static ClusterAccessException.Kind kindOf(ThrowingCall call) {
        ClusterAccessException failure = assertThrows(ClusterAccessException.class, call::run);
        return failure.kind();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void openNeverReturnsNullWithoutThrowing() {
        // Guards the shape of the contract itself: open() either yields a usable ClusterAccess or
        // throws typed. There is no third answer for a caller to misread.
        when(cloudAccountRepository.findByAwsAccountId(anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> eksProvider.open(target)).isInstanceOf(ClusterAccessException.class);
    }
}
