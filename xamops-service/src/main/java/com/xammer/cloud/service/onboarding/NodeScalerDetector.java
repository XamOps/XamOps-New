package com.xammer.cloud.service.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Pure detection of a cluster's node scaler from the {@code k8s-resources} payload's
 * {@code deployments[]} (design §6.3). For display and IAM rendering only; the automation agent
 * re-detects in-cluster before every removal (that is the safety check).
 *
 * <ul>
 *   <li>Karpenter: a Deployment whose image path contains {@code /karpenter/controller} or
 *       {@code public.ecr.aws/karpenter/}, or whose name is {@code karpenter}, with replicas &ge; 1.</li>
 *   <li>Cluster Autoscaler: a Deployment image containing {@code cluster-autoscaler}, replicas &ge; 1.</li>
 *   <li>both &rarr; BOTH; one &rarr; that one; none &rarr; NONE. Missing/unparseable &rarr; UNKNOWN.
 *       {@code EKS_AUTO_MODE} is decided by the server preflight (PF-COMPUTE) and takes precedence
 *       there, not here.</li>
 * </ul>
 */
@Service
public class NodeScalerDetector {

    public static final String KARPENTER = "KARPENTER";
    public static final String CLUSTER_AUTOSCALER = "CLUSTER_AUTOSCALER";
    public static final String BOTH = "BOTH";
    public static final String NONE = "NONE";
    public static final String UNKNOWN = "UNKNOWN";

    /** Detect from a parsed k8s-resources snapshot node. Returns UNKNOWN on a missing/odd shape. */
    public String detect(JsonNode snapshot) {
        if (snapshot == null || snapshot.isNull()) {
            return UNKNOWN;
        }
        JsonNode deployments = snapshot.get("deployments");
        if (deployments == null || !deployments.isArray()) {
            return UNKNOWN;
        }

        boolean karpenter = false;
        boolean clusterAutoscaler = false;
        for (JsonNode d : deployments) {
            int replicas = replicasOf(d);
            if (replicas < 1) {
                continue;
            }
            String name = text(d, "name").toLowerCase(Locale.ROOT);
            String images = imagesOf(d).toLowerCase(Locale.ROOT);

            if (images.contains("/karpenter/controller")
                    || images.contains("public.ecr.aws/karpenter/")
                    || "karpenter".equals(name)) {
                karpenter = true;
            }
            if (images.contains("cluster-autoscaler")) {
                clusterAutoscaler = true;
            }
        }

        if (karpenter && clusterAutoscaler) {
            return BOTH;
        }
        if (karpenter) {
            return KARPENTER;
        }
        if (clusterAutoscaler) {
            return CLUSTER_AUTOSCALER;
        }
        return NONE;
    }

    private static int replicasOf(JsonNode d) {
        JsonNode r = d.get("replicas");
        if (r == null || !r.isNumber()) {
            // Absent replicas defaults to 1 (a Deployment's default), so a running controller with
            // no explicit replica count still counts.
            return r == null ? 1 : 0;
        }
        return r.asInt();
    }

    private static String imagesOf(JsonNode d) {
        JsonNode images = d.get("images");
        StringBuilder sb = new StringBuilder();
        if (images != null && images.isArray()) {
            for (JsonNode img : images) {
                sb.append(img.asText("")).append(' ');
            }
        }
        // Some payloads carry a single "image" field instead.
        JsonNode single = d.get("image");
        if (single != null) {
            sb.append(single.asText("")).append(' ');
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? "" : v.asText("");
    }
}
