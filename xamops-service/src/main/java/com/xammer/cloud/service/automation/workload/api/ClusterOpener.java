package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Opens a cluster for the read-only history endpoints exactly the way endpoint 1 does: the
 * providers sorted by their declared {@code @Order}, the first that supports the target, one
 * connection per request. {@link VerticalAutomationReadService} keeps its own copy of the same three
 * lines, untouched, so endpoint 1's behaviour cannot move because of these endpoints.
 */
final class ClusterOpener {

    private final List<ClusterAccessProvider> providers;

    ClusterOpener(List<ClusterAccessProvider> accessProviders) {
        List<ClusterAccessProvider> ordered = new ArrayList<>(
                accessProviders == null ? List.of() : accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.providers = List.copyOf(ordered);
    }

    /**
     * @return an open connection the caller closes
     * @throws ClusterAccessException {@code CLUSTER_ACCESS_NOT_CONFIGURED} when no provider applies,
     *                                otherwise whatever the chosen provider raised
     */
    ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
        Optional<ClusterAccessProvider> provider = providers.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst();
        if (provider.isEmpty()) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "XamOps has no credential path into this cluster. Create an EKS access entry for"
                            + " the role registered for this account.");
        }
        return provider.get().open(target);
    }

    /**
     * The engine's inventory read, with a failure typed like an access failure: the API server
     * refusing the list calls (401/403) is access that is not configured, anything else is a
     * cluster that could not be read.
     */
    static ClusterWorkloadInventory.Inventory readInventory(ClusterWorkloadInventory inventory,
                                                           KubernetesClient client)
            throws ClusterAccessException {
        try {
            return inventory.read(client);
        } catch (RuntimeException e) {
            if (denied(e)) {
                throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                        "XamOps is not allowed to list this cluster's workloads: " + e.getMessage(), e);
            }
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_UNREACHABLE,
                    "XamOps could not read this cluster's workloads: " + e.getMessage(), e);
        }
    }

    /**
     * One read of the cluster's HPA objects, with a failure typed as {@link #readInventory} types
     * it: the API server refusing it (401/403) is access that is not configured, anything else is a
     * cluster that could not be read.
     */
    static <T> T readHpas(Supplier<T> read) throws ClusterAccessException {
        try {
            return read.get();
        } catch (RuntimeException e) {
            if (denied(e)) {
                throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                        "XamOps is not allowed to read this cluster's HPAs: " + e.getMessage(), e);
            }
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_UNREACHABLE,
                    "XamOps could not read this cluster's HPAs: " + e.getMessage(), e);
        }
    }

    /** A 401 or 403 from the API server anywhere in the cause chain (depth-bounded, cycle-safe). */
    private static boolean denied(Throwable failure) {
        Throwable node = failure;
        for (int depth = 0; node != null && depth < 16; depth++, node = node.getCause()) {
            if (node instanceof KubernetesClientException k8s && (k8s.getCode() == 401 || k8s.getCode() == 403)) {
                return true;
            }
        }
        return false;
    }
}
