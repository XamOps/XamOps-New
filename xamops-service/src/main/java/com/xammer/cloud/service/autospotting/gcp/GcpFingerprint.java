package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The GCP fingerprints (design-gcp.md §2.2, §2.9).
 *
 * <ul>
 *   <li>{@link #of}: the owned-field fingerprint (core §5.2): SHA-256 of the canonical JSON of the
 *       top-level {@code instanceTemplate}, {@code versions[]} as {@code (name, instanceTemplate,
 *       targetSize)} sorted by name, and {@code instanceFlexibilityPolicy}. {@code updatePolicy} is
 *       outside it once restored (a change is MINOR drift); {@code targetSize} and the autoscaler are
 *       outside it (DR-1).</li>
 *   <li>{@link #planHash}: plan staleness: the owned fields plus {@code updatePolicy},
 *       {@code autoHealingPolicies} and {@code allInstancesConfig}, everything the snapshot restores.
 *       MIG {@code targetSize} and {@code status} are excluded, so an autoscaler resize never makes a
 *       plan stale.</li>
 * </ul>
 * The raw MIG {@code fingerprint} is used by neither: it changes on every autoscaler resize.
 */
public final class GcpFingerprint {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private GcpFingerprint() {
    }

    public static String of(GcpMigFacts g) {
        return sha256(canonicalOwned(g));
    }

    public static String planHash(GcpMigFacts g) {
        Map<String, Object> m = ownedMap(g);
        m.put("updatePolicy", plain(g.updatePolicy()));
        m.put("autoHealingPolicies", plain(g.autoHealingPolicies()));
        m.put("allInstancesConfig", plain(g.allInstancesConfig()));
        return sha256(write(m));
    }

    /** The canonical JSON the owned fingerprint hashes (exposed for tests and the snapshot). */
    public static String canonicalOwned(GcpMigFacts g) {
        return write(ownedMap(g));
    }

    private static Map<String, Object> ownedMap(GcpMigFacts g) {
        Map<String, Object> m = new TreeMap<>();
        m.put("instanceTemplate", g.instanceTemplate());
        List<GcpMigFacts.Version> versions = new ArrayList<>(g.versions());
        versions.sort(Comparator.comparing(v -> v.name() == null ? "" : v.name()));
        List<Object> vs = new ArrayList<>();
        for (GcpMigFacts.Version v : versions) {
            Map<String, Object> e = new TreeMap<>();
            e.put("name", v.name());
            e.put("instanceTemplate", v.instanceTemplate());
            Map<String, Object> size = new TreeMap<>();
            size.put("fixed", v.targetSizeFixed());
            size.put("percent", v.targetSizePercent());
            e.put("targetSize", size);
            vs.add(e);
        }
        m.put("versions", vs);
        m.put("instanceFlexibilityPolicy", plain(g.instanceFlexibilityPolicy()));
        return m;
    }

    private static Object plain(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        return CANONICAL.convertValue(n, Object.class);
    }

    private static String write(Object o) {
        try {
            return CANONICAL.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("fingerprint serialization failed", e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
