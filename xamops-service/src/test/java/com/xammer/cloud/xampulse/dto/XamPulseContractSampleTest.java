package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialises the response DTOs that the frontend contract guards cover and pins the JSON in
 * {@code src/test/resources/xampulse/contract-samples/<Type>.json}. The TS test {@code xampulseContract.test.ts}
 * reads the same files, so a renamed or dropped Java field fails here (sample drift) and a renamed TS key fails there.
 *
 * Every sample is built with non-null values because the DTOs use NON_NULL: a null field would be omitted and the
 * key could not be checked. To regenerate the files after an intended change run with
 * {@code -Dxampulse.contract.update=true}.
 */
class XamPulseContractSampleTest {
    private static final Path SAMPLES = Path.of("src/test/resources/xampulse/contract-samples");
    private static final Path TS_GUARDS = Path.of("../frontend-app/src/types/xampulseGuards.ts");
    private static final Instant T = Instant.parse("2026-10-30T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static Map<String, Object> samples() {
        XpLogRow row = new XpLogRow("row-1", T, 1L, "us-east-1", "i-0abc", "nginx", "file", XpSeverity.ERROR, "boom",
                Map.of("k", "v"), "trace-1", "c1");
        XpHostRow host = new XpHostRow(1L, "i-0abc", "web-1", "us-east-1", "LINUX", "t3.large", "running", XpHealth.OK, 92, "ok",
                12.5, 40.0, 55.0, 1000.0, 2000.0, 0.4, List.of(10.0, 12.0), T, "ACTIVE", "T1", Map.of("env", "prod"));
        XpEvent event = new XpEvent(T, "DEPLOY", 1L, "i-0abc", "Deployed", "v2");
        XpTimelineEntry tl = new XpTimelineEntry(T, "OPENED", "opened");
        XpIncident incident = new XpIncident(7L, 1L, "CPU high", "HIGH", "OPEN", T, T, "ops", List.of(3L), List.of("i-0abc"), "summary", List.of(tl));
        XpEvidence evidence = new XpEvidence("METRIC", "cpu", "/xampulse/metrics");
        XpLane lane = new XpLane("CPU", XpHealth.OK, "fine");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("XpOverview", new XpOverview(false, XpAccountState.CONNECTED, T, 91,
                new XpKpis(3, 4, 1.5, 1024L, 1, 75.0), List.of("All quiet."),
                List.of(new XpAttentionItem("a1", "HIGH", "Disk filling", "detail", 1L, "i-0abc", T, "HIGH", List.of(evidence))),
                List.of(new XpHeatTile(1L, "i-0abc", "web-1", XpHealth.OK, 92, 12.5, 40.0))));
        m.put("XpHostRow", host);
        m.put("XpHostSummary", new XpHostSummary(host, List.of(lane), Map.of("system.cpu.utilization", List.of(new double[] {1.0, 2.0})),
                List.of(row), List.of(event), List.of(incident)));
        m.put("XpLogRow", row);
        m.put("XpLogSearchResponse", new XpLogSearchResponse(List.of(row), "cur", new XpScanned(10, 100), 5, false, new XpParsed("a:b")));
        m.put("XpLogHistogramResponse", new XpLogHistogramResponse(60, List.of(new XpHistogramBucket(T, Map.of("ERROR", 2L)))));
        m.put("XpLogFacetsResponse", new XpLogFacetsResponse(false, List.of(new XpFacet("service", List.of(new XpFacetValue("nginx", 5))))));
        m.put("XpLogPatternsResponse", new XpLogPatternsResponse(List.of(new XpPattern("p1", "error <N>", 4, 10.0, false, List.of(1L, 2L), T, "row-1", XpSeverity.ERROR))));
        m.put("XpMetricQueryResponse", new XpMetricQueryResponse(60, List.of(new XpMetricSeries("system.cpu.utilization", Map.of("instance_id", "i-0abc"),
                List.<Object[]>of(new Object[] {1000L, 1.5}), List.of(new double[] {1000, 0, 2}), List.<Object[]>of(new Object[] {1000L, 1.0}))), 3));
        m.put("XpAlertRule", new XpAlertRule(3L, 1L, "CPU above 90%", true, "HIGH", new XpAlertScope(List.of(1L), List.of("i-0abc"), "env=prod"),
                new XpCondition("METRIC_THRESHOLD", "system.cpu.utilization", List.of(new XpMetricFilter("state", "=", "idle")), "q", "avg", "5m", ">", 90.0, "10m", "KEEP", 7),
                new XpGrouping(List.of("instance_id"), "30m"), new XpRouting(List.of(1L), false, "https://runbook.example.com")));
        m.put("XpIncident", incident);
        m.put("XpInsight", new XpInsight("i1", "ANOMALY", "LOW", "Title", "Body", 1L, "i-0abc", List.of(evidence), "HIGH", T));
        m.put("XpTrustPanel", new XpTrustPanel(List.of("cpu"), List.of("secrets"), List.of(new XpRedaction("email", "a@b.c")),
                new XpTrustWhere("us-east-1", 7, "bucket", "us-east-1", "alias/key"), List.of("admins"), List.of("TLS"),
                new XpPermissionsDiff(List.of("s3:PutObject"), "note", List.of("grant"), "none"), "sha"));
        m.put("XpSetupOverview", new XpSetupOverview(XpAccountState.CONNECTED, false, "us-east-1", new XpBucketRef("bucket", "us-east-1", "CREATE"),
                List.of(new XpSetupRegion("us-east-1", 4, "OK", "INSTANCES", 3, 3)), new XpSetupInstances(4, 4, 3, 3, 3, 1), 75, T,
                new XpSetupOverview.XpArchiveStatus("FAILING", 3, "IllegalStateException", T)));
        m.put("XpUsage", new XpUsage("7d", List.of(new XpUsageDay("2026-10-30", "LOGS", 10, 5)), List.of(new XpUsageSource("system", 10, 100.0)),
                1L, 2L, 1.5, 10.0));
        m.put("XpAccountRef", new XpAccountRef(1L, "123456789012", "prod", XpAccountState.CONNECTED, false));
        return m;
    }

    /**
     * Pass-2 response types, pinned like the others. Kept as a separate map, but included in the
     * TS-guard parity check now that {@code xampulseGuards.ts} has a guard for each.
     */
    private static Map<String, Object> pass2Samples() {
        XpLogRow row = new XpLogRow("row-1", T, 1L, "us-east-1", "i-0abc", "nginx", "file", XpSeverity.ERROR, "boom",
                Map.of("k", "v"), "trace-1", "c1");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("XpAgentFleet", new XpAgentFleet(new XpAgentFleet.Counts(1, 1, 0, 0, 0), List.of(
                new XpAgentHealth("i-0abc", "web-1", "ACTIVE", "1.2.3", T, 2048L, 0L,
                        List.of(new XpAgentHealth.Source("nginx", "OK", "none")), List.of("e1")))));
        m.put("XpArchiveSearchJob", new XpArchiveSearchJob("job-1", "DONE",
                new XpArchiveEstimate(1024L, 2L, 0.01, List.of("STANDARD"), "ESTIMATE"), 2, 2, List.of(row), true, "ARCHIVE_INTERNAL", T, T));
        return m;
    }

    @Test
    void pass2SamplesMatchThePinnedJsonAndHaveNoNulls() throws IOException {
        boolean update = Boolean.getBoolean("xampulse.contract.update");
        if (update) {
            Files.createDirectories(SAMPLES);
        }
        for (Map.Entry<String, Object> e : pass2Samples().entrySet()) {
            JsonNode actual = mapper.readTree(mapper.writeValueAsString(e.getValue()));
            assertThat(containsNull(actual)).as("%s sample must not contain null values", e.getKey()).isFalse();
            Path file = SAMPLES.resolve(e.getKey() + ".json");
            if (update) {
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n", StandardCharsets.UTF_8);
            }
            assertThat(file).as("run with -Dxampulse.contract.update=true to create %s", file).exists();
            assertThat(actual).as("%s drifted from %s", e.getKey(), file).isEqualTo(mapper.readTree(Files.readString(file)));
        }
    }

    @Test
    void samplesMatchThePinnedJsonAndHaveNoNulls() throws IOException {
        boolean update = Boolean.getBoolean("xampulse.contract.update");
        if (update) {
            Files.createDirectories(SAMPLES);
        }
        for (Map.Entry<String, Object> e : samples().entrySet()) {
            // Round trip through text so numeric node types (long vs int) match what readTree gives for the file.
            JsonNode actual = mapper.readTree(mapper.writeValueAsString(e.getValue()));
            assertThat(containsNull(actual)).as("%s sample must not contain null values", e.getKey()).isFalse();
            Path file = SAMPLES.resolve(e.getKey() + ".json");
            if (update) {
                Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n", StandardCharsets.UTF_8);
            }
            assertThat(file).as("run with -Dxampulse.contract.update=true to create %s", file).exists();
            assertThat(actual).as("%s drifted from %s", e.getKey(), file).isEqualTo(mapper.readTree(Files.readString(file)));
        }
    }

    @Test
    void everySampleCarriesTheKeysTheTypeScriptGuardsRequire() throws IOException {
        if (!Files.exists(TS_GUARDS)) {
            return; // module built without the frontend checkout (for example in a Docker build)
        }
        Map<String, List<String>> required = parseGuards(Files.readString(TS_GUARDS));
        assertThat(required.keySet()).as("guard types parsed from xampulseGuards.ts").isNotEmpty();
        Map<String, Object> samples = new LinkedHashMap<>(samples());
        samples.putAll(pass2Samples());
        assertThat(new TreeSet<>(samples.keySet())).as("every guarded type has a Java sample and vice versa").isEqualTo(new TreeSet<>(required.keySet()));
        for (Map.Entry<String, List<String>> e : required.entrySet()) {
            JsonNode json = mapper.valueToTree(samples.get(e.getKey()));
            List<String> missing = new ArrayList<>();
            e.getValue().forEach(k -> {
                if (!json.has(k)) {
                    missing.add(k);
                }
            });
            assertThat(missing).as("%s: keys required by the TS guards but absent from the Java JSON", e.getKey()).isEmpty();
        }
    }

    static Map<String, List<String>> parseGuards(String ts) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^\\s*(Xp\\w+):\\s*\\[([^\\]]*)\\]", Pattern.MULTILINE).matcher(ts);
        while (m.find()) {
            List<String> keys = new ArrayList<>();
            Matcher k = Pattern.compile("'([^']+)'").matcher(m.group(2));
            while (k.find()) {
                keys.add(k.group(1));
            }
            out.put(m.group(1), keys);
        }
        return out;
    }

    private static boolean containsNull(JsonNode n) {
        if (n.isNull()) {
            return true;
        }
        for (JsonNode c : n) {
            if (containsNull(c)) {
                return true;
            }
        }
        return false;
    }
}
