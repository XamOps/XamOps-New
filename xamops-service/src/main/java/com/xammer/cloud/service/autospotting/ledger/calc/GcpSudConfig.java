package com.xammer.cloud.service.autospotting.ledger.calc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The versioned SUD config {@code gcp-sud-v1} (design-ledger.md §3.9, PO-08): per machine series the maximum
 * sustained use discount and the price multiplier of each quarter of the month. A series with no entry is not
 * guessed: the entry is UNKNOWN with {@code MISSING_SUD_RATE}. Decimals are read from their text, never as doubles.
 */
public final class GcpSudConfig {
    public static final String RESOURCE = "/autospotting/gcp-sud-v1.json";
    private static final GcpSudConfig DEFAULT = load();

    /** One series' discount: maximum rate and quarter multipliers (empty when it earns none). */
    public record Series(BigDecimal maxRate, List<BigDecimal> tiers) {}

    private final String version;
    private final Map<String, Series> series;

    private GcpSudConfig(String version, Map<String, Series> series) {
        this.version = version;
        this.series = Map.copyOf(series);
    }

    public static GcpSudConfig v1() {
        return DEFAULT;
    }

    public String version() {
        return version;
    }

    public Optional<Series> forSeries(String machineSeries) {
        return Optional.ofNullable(series.get(machineSeries));
    }

    private static GcpSudConfig load() {
        try (InputStream in = GcpSudConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, Series> m = new TreeMap<>();
            root.path("series").fields().forEachRemaining(e -> {
                List<BigDecimal> tiers = new ArrayList<>();
                e.getValue().path("tiers").forEach(t -> tiers.add(new BigDecimal(t.asText())));
                m.put(e.getKey(), new Series(new BigDecimal(e.getValue().path("maxRate").asText()), tiers));
            });
            return new GcpSudConfig(root.path("version").asText(), m);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
