package com.xammer.cloud.service.automation.workload.access;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.K8sClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Test and bring-your-own-credential access path: a stored kubeconfig, or an explicitly supplied
 * API server + CA + ServiceAccount token.
 *
 * <p>Exists for the <strong>deferred live-cluster test slice</strong>, which needs to reach a test
 * cluster without an EKS access entry and without AWS credentials. It is also the path a customer
 * who already uploaded a kubeconfig takes today for Kubescape and OpenCost, so keeping it typed the
 * same way means the engine's gate rendering does not change with the credential source.
 *
 * <p>Surfaces the <strong>same two typed outcomes</strong> as
 * {@link EksClusterAccessProvider}. Since the B7 fix, a stored kubeconfig that declares no cluster
 * CA produces {@code CLUSTER_ACCESS_NOT_CONFIGURED} rather than an insecure connection.
 *
 * <p><strong>Tried second</strong> ({@link #PRECEDENCE}): for a cluster reached through a connected
 * AWS account, the customer's own role is the credential of record, and this is the fallback. Both
 * {@code supports} predicates are true for a registered EKS cluster, so the precedence — not
 * component-scan ordering — is what decides.
 */
@Service
@Order(KubeconfigClusterAccessProvider.PRECEDENCE)
public class KubeconfigClusterAccessProvider implements ClusterAccessProvider {

    /** Lower wins. Behind {@link EksClusterAccessProvider#PRECEDENCE}. */
    public static final int PRECEDENCE = 20;

    private static final Logger logger = LoggerFactory.getLogger(KubeconfigClusterAccessProvider.class);

    private static final String PROBE_NAMESPACE = "kube-system";

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final K8sClientFactory k8sClientFactory;

    public KubeconfigClusterAccessProvider(KubernetesClusterConfigRepository clusterConfigRepository,
                                           K8sClientFactory k8sClientFactory) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.k8sClientFactory = k8sClientFactory;
    }

    @Override
    public String providerName() {
        return "kubeconfig";
    }

    /**
     * Structural check only: whether a cluster config is addressable at all. Whether that config
     * actually carries a kubeconfig is a DB read, and {@code supports} performs no I/O — that case
     * comes back from {@link #open} as {@code CLUSTER_ACCESS_NOT_CONFIGURED}.
     */
    @Override
    public boolean supports(ClusterTarget target) {
        return target != null && target.clusterConfigId() != null;
    }

    @Override
    public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
        if (!supports(target)) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "This cluster is not registered with XamOps.");
        }

        String kubeconfig = requireKubeconfig(target);
        return open(target, () -> k8sClientFactory.createFromKubeconfig(kubeconfig));
    }

    /**
     * Opens a connection from components supplied by the caller rather than from the database — the
     * shape the deferred live tests use, where the cluster is reached with a ServiceAccount token.
     *
     * @param caCertData the cluster CA, base64 PEM or raw PEM; <strong>mandatory</strong> since the
     *                   B7 fix, because there is no insecure fallback
     */
    public ClusterAccess openWithServiceAccountToken(ClusterTarget target, String apiServerUrl,
                                                     String caCertData, String token)
            throws ClusterAccessException {
        return open(target, () -> k8sClientFactory.createFromComponents(apiServerUrl, caCertData, token));
    }

    private ClusterAccess open(ClusterTarget target, ClientSupplier supplier) throws ClusterAccessException {
        KubernetesClient client = null;
        try {
            client = supplier.get();
            // Prove the server answers, so the caller never gets a connection that fails on first use.
            client.namespaces().withName(PROBE_NAMESPACE).get();
            return new ClusterAccess(client, client.getMasterUrl() == null ? null : client.getMasterUrl().toString(),
                    target.clusterName(), target.region(), providerName());
        } catch (Exception e) {
            closeQuietly(client);
            logger.warn("Could not open {} by kubeconfig: {}", target.describe(), e.toString());
            throw ClusterAccessFailures.classify(
                    "Connecting to the API server of cluster " + target.clusterName() + " failed", e);
        }
    }

    private String requireKubeconfig(ClusterTarget target) throws ClusterAccessException {
        Optional<KubernetesClusterConfig> config = clusterConfigRepository.findById(target.clusterConfigId());
        String kubeconfig = config.map(KubernetesClusterConfig::getKubeconfigYaml).orElse(null);
        if (kubeconfig == null || kubeconfig.isBlank()) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "No kubeconfig is stored for this cluster, so XamOps cannot reach its API server.");
        }
        return kubeconfig;
    }

    private static void closeQuietly(KubernetesClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            logger.debug("Ignoring failure while closing a half-open Kubernetes client", e);
        }
    }

    /** Lets both entry points share one failure-classification path. */
    @FunctionalInterface
    private interface ClientSupplier {
        KubernetesClient get();
    }
}
