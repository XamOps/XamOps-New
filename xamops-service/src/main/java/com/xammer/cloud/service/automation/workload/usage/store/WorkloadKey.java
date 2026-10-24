package com.xammer.cloud.service.automation.workload.usage.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The {@code k8s_workload_identity.workload_key}: lowercase hex sha256 of
 * {@code kind/namespace/name/container} (UTF-8), always 64 characters. A fixed-width key keeps the
 * {@code (cluster_config_id, workload_key)} unique index small no matter how long the names are.
 */
public final class WorkloadKey {

    private WorkloadKey() {
    }

    public static String of(String kind, String namespace, String name, String container) {
        String natural = kind + "/" + namespace + "/" + name + "/" + container;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(natural.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
