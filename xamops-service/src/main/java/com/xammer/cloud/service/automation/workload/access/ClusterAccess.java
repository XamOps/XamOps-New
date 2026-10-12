package com.xammer.cloud.service.automation.workload.access;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * An open, authenticated connection to one cluster, plus the metadata a caller needs to report
 * where its data came from.
 *
 * <p>{@link AutoCloseable} because a fabric8 {@link KubernetesClient} owns an HTTP client and its
 * thread pool. <strong>The caller closes it</strong>, which is why
 * {@link ClusterAccessProvider#open(ClusterTarget)} says so on the method.
 */
public final class ClusterAccess implements AutoCloseable {

    private final KubernetesClient client;
    private final String apiServerUrl;
    private final String clusterName;
    private final String region;
    private final String providerName;

    public ClusterAccess(KubernetesClient client, String apiServerUrl, String clusterName,
                         String region, String providerName) {
        if (client == null) {
            throw new IllegalArgumentException("client is mandatory");
        }
        this.client = client;
        this.apiServerUrl = apiServerUrl;
        this.clusterName = clusterName;
        this.region = region;
        this.providerName = providerName;
    }

    public KubernetesClient client() {
        return client;
    }

    public String apiServerUrl() {
        return apiServerUrl;
    }

    public String clusterName() {
        return clusterName;
    }

    public String region() {
        return region;
    }

    /** Which provider opened this connection, for the {@code executionPath} the API reports. */
    public String providerName() {
        return providerName;
    }

    @Override
    public void close() {
        client.close();
    }
}
