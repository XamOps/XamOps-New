package com.xammer.cloud.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.onboarding.NodeScalerDetector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-10 (design §6.3): node-scaler detection fixtures — Karpenter Helm install, Cluster Autoscaler
 * in kube-system, CA scaled to 0 (NONE), a lookalike name with no CA image (NONE), both present
 * (BOTH), and a missing/odd payload (UNKNOWN).
 */
class NodeScalerDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NodeScalerDetector detector = new NodeScalerDetector();

    private String detect(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        return detector.detect(node);
    }

    @Test
    void karpenterHelmInstall_isKarpenter() throws Exception {
        String json = """
            {"deployments":[
              {"name":"karpenter","replicas":2,"images":["public.ecr.aws/karpenter/controller:v0.37.0"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.KARPENTER);
    }

    @Test
    void clusterAutoscalerInKubeSystem_isClusterAutoscaler() throws Exception {
        String json = """
            {"deployments":[
              {"name":"cluster-autoscaler","replicas":1,
               "images":["registry.k8s.io/autoscaling/cluster-autoscaler:v1.30.0"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.CLUSTER_AUTOSCALER);
    }

    @Test
    void caScaledToZero_isNone() throws Exception {
        String json = """
            {"deployments":[
              {"name":"cluster-autoscaler","replicas":0,
               "images":["registry.k8s.io/autoscaling/cluster-autoscaler:v1.30.0"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.NONE);
    }

    @Test
    void lookalikeNameWithoutCaImage_isNone() throws Exception {
        String json = """
            {"deployments":[
              {"name":"my-cluster-autoscaler-dashboard","replicas":1,
               "images":["example.com/some/dashboard:1.0"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.NONE);
    }

    @Test
    void bothPresent_isBoth() throws Exception {
        String json = """
            {"deployments":[
              {"name":"karpenter","replicas":1,"images":["public.ecr.aws/karpenter/controller:v0.37.0"]},
              {"name":"cluster-autoscaler","replicas":1,"images":["registry.k8s.io/autoscaling/cluster-autoscaler:v1.30.0"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.BOTH);
    }

    @Test
    void missingDeployments_isUnknown() throws Exception {
        assertThat(detect("{\"pods\":[]}")).isEqualTo(NodeScalerDetector.UNKNOWN);
        assertThat(detector.detect(null)).isEqualTo(NodeScalerDetector.UNKNOWN);
    }

    @Test
    void karpenterByImagePathWhenNameDiffers() throws Exception {
        String json = """
            {"deployments":[
              {"name":"np-controller","replicas":1,"images":["public.ecr.aws/karpenter/controller:v1"]}
            ]}""";
        assertThat(detect(json)).isEqualTo(NodeScalerDetector.KARPENTER);
    }
}
