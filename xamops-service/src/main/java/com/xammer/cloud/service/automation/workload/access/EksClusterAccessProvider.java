package com.xammer.cloud.service.automation.workload.access;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.EksTokenGenerator;
import com.xammer.cloud.service.K8sClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;

import java.util.List;
import java.util.Optional;

/**
 * Production access path: reach the customer's EKS API server directly, authenticated as the
 * customer's XamOps role through an EKS access entry.
 *
 * <p>This is the <strong>optional fast path</strong>, not the primary one. The primary execution
 * path is agent-mediated, because the agent polls outbound-only and therefore already reaches
 * private-endpoint clusters, and because usage history has to be computed inside the cluster either
 * way (the backend keeps no history: {@code agent_data_cache} is unique on
 * {@code (cluster_config_id, data_type)} and every push overwrites the last).
 *
 * <p>The logic is a re-implementation of
 * {@code EksService.getKubernetesClientForCustomerCluster} in this package.
 * <strong>{@code EksService} is not edited</strong> — it stays a public API that other code calls.
 * The differences from it are deliberate:
 *
 * <ul>
 *   <li>The account is resolved by <strong>AWS account number</strong> and then checked against the
 *       target's {@code clientId}, so a cluster in another tenant cannot be opened even if the
 *       caller's entitlement check were bypassed. {@code EksService} resolves by
 *       {@code findByProviderAccountId} with no tenancy assertion.</li>
 *   <li>Failures come out as the two typed {@link ClusterAccessException.Kind}s rather than as
 *       {@code RuntimeException("Failed to create K8s client for cluster: …")}.</li>
 *   <li>The client is built through the <strong>B7-fixed</strong>
 *       {@link K8sClientFactory#createFromComponents}, so the CA that {@code describeCluster}
 *       returns is now actually used as the trust anchor instead of being discarded.</li>
 * </ul>
 *
 * <p>Nothing invokes this while {@code xamops.automation.vertical.enabled} is false, which is its
 * default in every profile.
 */
@Service
public class EksClusterAccessProvider implements ClusterAccessProvider {

    private static final Logger logger = LoggerFactory.getLogger(EksClusterAccessProvider.class);

    /** In-role under the documented ClusterRole ({@code namespaces: [get, list, watch]}). */
    private static final String PROBE_NAMESPACE = "kube-system";

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final EksTokenGenerator eksTokenGenerator;
    private final K8sClientFactory k8sClientFactory;

    public EksClusterAccessProvider(CloudAccountRepository cloudAccountRepository,
                                    AwsClientProvider awsClientProvider,
                                    EksTokenGenerator eksTokenGenerator,
                                    K8sClientFactory k8sClientFactory) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.eksTokenGenerator = eksTokenGenerator;
        this.k8sClientFactory = k8sClientFactory;
    }

    @Override
    public String providerName() {
        return "eks-direct";
    }

    @Override
    public boolean supports(ClusterTarget target) {
        return target != null
                && target.awsAccountId() != null && !target.awsAccountId().isBlank()
                && target.region() != null && !target.region().isBlank();
    }

    @Override
    public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
        if (!supports(target)) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "This cluster has no AWS account and region recorded, so the EKS API cannot be "
                            + "addressed. Re-register the cluster from Account Manager.");
        }

        CloudAccount account = requireEntitledAwsAccount(target);

        String apiServer;
        String caCertData;
        String token;
        try {
            EksClient eks = awsClientProvider.getEksClient(account, target.region());
            Cluster cluster = eks.describeCluster(b -> b.name(target.clusterName())).cluster();
            if (cluster == null || cluster.endpoint() == null || cluster.endpoint().isBlank()) {
                throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                        "EKS returned no API endpoint for cluster " + target.clusterName()
                                + ", so it cannot be reached.");
            }
            apiServer = cluster.endpoint();
            caCertData = cluster.certificateAuthority() == null ? null : cluster.certificateAuthority().data();
            token = eksTokenGenerator.generateTokenForCluster(
                    account.getAwsAccountId(), target.clusterName(), target.region());
        } catch (ClusterAccessException alreadyTyped) {
            throw alreadyTyped;
        } catch (Exception e) {
            logger.warn("Could not describe EKS cluster for {}: {}", target.describe(), e.toString());
            throw ClusterAccessFailures.classify(
                    "Reading the EKS description of cluster " + target.clusterName() + " failed", e);
        }

        return openVerified(target, apiServer, caCertData, token);
    }

    /**
     * Builds the client and proves the API server actually answers, so a caller never receives a
     * {@link ClusterAccess} that fails on first use.
     *
     * <p>The probe is a {@code get} on the {@value #PROBE_NAMESPACE} namespace: the cheapest call
     * the documented ClusterRole permits. A 401/403 means the access entry or its ClusterRole
     * binding is missing; a timeout or DNS failure means the endpoint is private or unroutable.
     */
    private ClusterAccess openVerified(ClusterTarget target, String apiServer, String caCertData, String token)
            throws ClusterAccessException {
        KubernetesClient client = null;
        try {
            client = k8sClientFactory.createFromComponents(apiServer, caCertData, token);
            client.namespaces().withName(PROBE_NAMESPACE).get();
            return new ClusterAccess(client, apiServer, target.clusterName(), target.region(), providerName());
        } catch (Exception e) {
            closeQuietly(client);
            logger.warn("Could not open the EKS API of {}: {}", target.describe(), e.toString());
            throw ClusterAccessFailures.classify(
                    "Connecting to the API server of cluster " + target.clusterName() + " failed", e);
        }
    }

    /**
     * Resolves the AWS account and asserts it belongs to the target's tenant.
     *
     * <p>The 12-digit number is tried left-padded and raw, AWS rows only — the same resolution
     * {@code CloudAccountAccessService.requireAwsAccess} performs, so an account stored as
     * {@code 012345678901} resolves from either form. There is no primary-key fallback: the value is
     * an AWS account number, never an id.
     */
    private CloudAccount requireEntitledAwsAccount(ClusterTarget target) throws ClusterAccessException {
        String raw = target.awsAccountId().trim();
        Optional<CloudAccount> resolved = candidates(raw).stream()
                .flatMap(candidate -> cloudAccountRepository.findByAwsAccountId(candidate).stream())
                .filter(a -> a != null && "AWS".equalsIgnoreCase(a.getProvider()))
                .filter(a -> a.getClient() != null && target.clientId().equals(a.getClient().getId()))
                .findFirst();

        if (resolved.isEmpty()) {
            // Same message whether the account is unknown, not AWS or another tenant's: a
            // distinguishing message would turn this into an oracle for which accounts exist.
            logger.warn("No entitled AWS account for {}", target.describe());
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "This cluster's AWS account is not connected to XamOps for this workspace.");
        }
        return resolved.get();
    }

    private static List<String> candidates(String raw) {
        if (!raw.matches("^[0-9]{1,12}$")) {
            return List.of(raw);
        }
        String padded = "0".repeat(12 - raw.length()) + raw;
        return padded.equals(raw) ? List.of(raw) : List.of(padded, raw);
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
}
