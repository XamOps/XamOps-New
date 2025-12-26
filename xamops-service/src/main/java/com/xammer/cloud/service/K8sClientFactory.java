package com.xammer.cloud.service;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class K8sClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(K8sClientFactory.class);

    /**
     * Create Kubernetes client from kubeconfig YAML string
     */
    public KubernetesClient createFromKubeconfig(String kubeconfigYaml) {
        try {
            logger.info("Creating Kubernetes client from kubeconfig");

            // Parse kubeconfig YAML
            Config config = Config.fromKubeconfig(kubeconfigYaml);

            // FIX: Disable SSL verification to prevent PKIX path validation errors
            // This allows connection even if the local Java truststore doesn't recognize
            // the EKS CA
            config.setTrustCerts(true);

            // Create client
            KubernetesClient client = new KubernetesClientBuilder()
                    .withConfig(config)
                    .build();

            // Test connection
            String version = client.getKubernetesVersion().getGitVersion();
            logger.info("✅ Successfully connected to Kubernetes cluster, version: {}", version);

            return client;

        } catch (Exception e) {
            logger.error("❌ Failed to create Kubernetes client", e);
            throw new RuntimeException("Could not connect to Kubernetes cluster: " + e.getMessage(), e);
        }
    }

    /**
     * Create Kubernetes client from individual components
     */
    public KubernetesClient createFromComponents(String apiServer, String caCertData, String token) {
        try {
            logger.info("Creating Kubernetes client for API server: {}", apiServer);

            Config config = new ConfigBuilder()
                    .withMasterUrl(apiServer)
                    .withCaCertData(caCertData)
                    .withOauthToken(token)
                    .withTrustCerts(true) // FIX: Trust certs here as well
                    .build();

            KubernetesClient client = new KubernetesClientBuilder()
                    .withConfig(config)
                    .build();

            // Test connection
            String version = client.getKubernetesVersion().getGitVersion();
            logger.info("✅ Successfully connected to Kubernetes cluster, version: {}", version);

            return client;

        } catch (Exception e) {
            logger.error("❌ Failed to create Kubernetes client", e);
            throw new RuntimeException("Could not connect to Kubernetes cluster: " + e.getMessage(), e);
        }
    }
}