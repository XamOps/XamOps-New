package com.xammer.cloud.service.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the permission manifest (design §5.3, §5.4) and owns the single node-removal predicate
 * (F-4). The rendered write policy has exactly FOUR actions — the shipped dev JSON's unused
 * {@code ec2:DescribeInstances} is dropped (design-delta §3) — and the terminate statement keeps the
 * SCOPED ASG ARN (not {@code "*"}) plus the cluster-name {@code StringEquals} and the
 * {@code Null eks:nodegroup-name = "false"} managed-nodegroup guard. Cluster/region/account are
 * templated (rendered to the {@code aws} partition).
 */
@Service
public class PermissionManifestService {

    /** Canonical JSON for hashing: keys sorted, no whitespace. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final ObjectMapper PLAIN = new ObjectMapper();

    /** The four write actions the autoscaler actually calls (design §5.3 action→call table). */
    public static final List<String> WRITE_DESCRIBE_ACTIONS = List.of(
            "autoscaling:DescribeAutoScalingInstances",
            "autoscaling:DescribeAutoScalingGroups");
    public static final String EKS_DESCRIBE_NODEGROUP = "eks:DescribeNodegroup";
    public static final String TERMINATE_ACTION = "autoscaling:TerminateInstanceInAutoScalingGroup";

    /**
     * The one node-removal predicate (F-4): true only for a {@code NONE} node scaler with
     * {@code NODE_CONSOLIDATION} selected and PF-COMPUTE PASS. Evaluated once at ENABLE_AUTOMATION
     * token issue and stored on the token; the same value drives the manifest, the CFN parameter,
     * {@code ENABLE_NODE_REMOVAL}, and the UI copy.
     */
    public boolean nodeRemoval(String nodeScaler, List<String> features, boolean pfComputePass) {
        return NodeScalerDetector.NONE.equals(nodeScaler)
                && features != null && features.contains(OnboardingFeatureCatalog.NODE_CONSOLIDATION)
                && pfComputePass;
    }

    /** The read policy (phase 1) — two actions, scoped to this cluster. */
    public Map<String, Object> readPolicy(String cluster, String region, String account) {
        List<Object> statements = new ArrayList<>();
        statements.add(statement("ListThisClustersNodegroups", List.of("eks:ListNodegroups"),
                "arn:aws:eks:" + region + ":" + account + ":cluster/" + cluster, null));
        statements.add(statement("DescribeThisClustersNodegroups", List.of(EKS_DESCRIBE_NODEGROUP),
                "arn:aws:eks:" + region + ":" + account + ":nodegroup/" + cluster + "/*/*", null));
        return policy(statements);
    }

    /**
     * The write policy (phase 2) — FOUR actions, scoped terminate ARN, managed-nodegroup guard.
     * {@code null} when {@code nodeRemoval} is false (identity-only write role).
     */
    public Map<String, Object> writePolicy(String cluster, String region, String account, boolean nodeRemoval) {
        if (!nodeRemoval) {
            return null;
        }
        List<Object> statements = new ArrayList<>();
        statements.add(statement("DescribeNodeCapacity", WRITE_DESCRIBE_ACTIONS, "*", null));
        statements.add(statement("DescribeThisClustersNodegroups", List.of(EKS_DESCRIBE_NODEGROUP),
                "arn:aws:eks:" + region + ":" + account + ":nodegroup/" + cluster + "/*/*", null));

        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("StringEquals", Map.of("aws:ResourceTag/eks:cluster-name", cluster));
        condition.put("Null", Map.of("aws:ResourceTag/eks:nodegroup-name", "false"));
        statements.add(statement("RemoveDrainedNodeFromThisClustersManagedNodegroups",
                List.of(TERMINATE_ACTION),
                "arn:aws:autoscaling:" + region + ":" + account + ":autoScalingGroup:*:autoScalingGroupName/*",
                condition));
        return policy(statements);
    }

    /** Build the full manifest DTO (design §5.4). */
    public Map<String, Object> manifest(String phase, String cluster, String region, String account,
                                        String identityMode, String nodeScaler, List<String> features,
                                        boolean nodeRemoval, List<Map<String, Object>> kubernetes,
                                        List<String> adminPermissions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "eks-v1");
        m.put("phase", phase);
        m.put("clusterName", cluster);
        m.put("region", region);
        m.put("accountId", account);
        m.put("identityMode", identityMode);
        m.put("nodeScaler", nodeScaler);
        m.put("kubernetes", kubernetes == null ? List.of() : kubernetes);

        List<Map<String, Object>> aws = new ArrayList<>();
        if ("AUTOMATION".equals(phase)) {
            Map<String, Object> writeEntry = new LinkedHashMap<>();
            writeEntry.put("role", "write");
            writeEntry.put("boundTo", "xamops-system/xamops-automation");
            writeEntry.put("policy", writePolicy(cluster, region, account, nodeRemoval));
            aws.add(writeEntry);
        } else {
            Map<String, Object> readEntry = new LinkedHashMap<>();
            readEntry.put("role", "read");
            readEntry.put("boundTo", "xamops-system/xamops-agent-sa");
            readEntry.put("policy", readPolicy(cluster, region, account));
            aws.add(readEntry);
        }
        m.put("aws", aws);
        m.put("adminPermissions", adminPermissions == null ? List.of() : adminPermissions);
        m.put("features", features == null ? List.of() : features);
        m.put("nodeRemoval", nodeRemoval);
        m.put("notes", List.of(
                "Drained nodes are removed by terminating the instance in its nodegroup's Auto Scaling "
                        + "group and lowering desired capacity by one. Never below the nodegroup minimum."));
        m.put("hash", hash(m));
        return m;
    }

    /** SHA-256 of the canonical JSON of every field except {@code hash}. */
    public String hash(Map<String, Object> manifestWithoutHash) {
        Map<String, Object> copy = new LinkedHashMap<>(manifestWithoutHash);
        copy.remove("hash");
        try {
            String canonical = CANONICAL.writeValueAsString(copy);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash permission manifest", e);
        }
    }

    private static Map<String, Object> statement(String sid, List<String> actions, String resource,
                                                 Map<String, Object> condition) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Sid", sid);
        s.put("Effect", "Allow");
        s.put("Action", actions);
        s.put("Resource", resource);
        if (condition != null) {
            s.put("Condition", condition);
        }
        return s;
    }

    private static Map<String, Object> policy(List<Object> statements) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("Version", "2012-10-17");
        p.put("Statement", statements);
        return p;
    }

    /** Serialise a manifest/policy to JSON for serving. */
    public String toJson(Object manifest) {
        try {
            return PLAIN.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise permission manifest", e);
        }
    }
}
