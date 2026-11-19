package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The versioned table of billed vCPU for GCP shared-core machine types, {@code gcp-shared-core-v1}
 * (PD-09, design-gcp.md §6.4, design-ledger.md §4.2). The billed fraction is read from here, never
 * from {@code guestCpus} (2 for the E2 shared-core types). A shared-core type missing from the table
 * has no billed vCPU: it is {@code GCP_SHARED_CORE_UNSUPPORTED} and "Not priced".
 */
public final class GcpSharedCoreTable {
    public static final String RESOURCE = "/autospotting/gcp-shared-core-v1.json";
    private static final GcpSharedCoreTable DEFAULT = load();

    private final String version;
    private final Map<String, BigDecimal> fractions;

    GcpSharedCoreTable(String version, Map<String, BigDecimal> fractions) {
        this.version = version;
        this.fractions = Map.copyOf(fractions);
    }

    public static GcpSharedCoreTable v1() {
        return DEFAULT;
    }

    public String version() {
        return version;
    }

    /** The billed vCPU of {@code machineType}, or empty when the type is not in the table. */
    public Optional<BigDecimal> billedVcpu(String machineType) {
        return Optional.ofNullable(fractions.get(machineType));
    }

    public Map<String, BigDecimal> entries() {
        return fractions;
    }

    private static GcpSharedCoreTable load() {
        try (InputStream in = GcpSharedCoreTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            root.path("types").fields().forEachRemaining(e -> m.put(e.getKey(), new BigDecimal(e.getValue().asText())));
            return new GcpSharedCoreTable(root.path("version").asText(), m);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
