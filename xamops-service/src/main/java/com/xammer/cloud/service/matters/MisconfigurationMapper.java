package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The XamLens Misconfigurations page from the per-datastore {@code issues/overview} answers, grouped by
 * {@code policy_id} (build 5, plan 1.3 and 1.4).
 *
 * <p>Dependency-free and defensive, like {@link DatastoreRowMapper}: {@code path()} reads, and a missing
 * field is null. A finding's {@code details} and {@code compliance.clause} are never read.
 */
@Component
@Slf4j
public class MisconfigurationMapper {

    private static final String CLOUD = "cloud";
    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /** One finding of one datastore. {@code framework} is its {@code compliance.name}, or null. */
    public record Issue(String policyId, String title, String source, String severity, String description,
                        String remediation, String cli, String learnMore, String framework) {
    }

    /** One datastore read: its labelled list row, its raw last scan time and its findings. */
    public record Source(DatastoreRow row, Long lastScannedAt, List<Issue> issues) {
    }

    // ── one datastore ────────────────────────────────────────────────────────────────────────────

    /**
     * Every {@code risk[]} finding of an {@code issues/overview} body, in vendor order. A finding whose
     * {@code policy_id} {@link DatastoreParams#isPolicyId} refuses is skipped, because the drawer endpoint
     * could not address it; the count is logged, never the values.
     */
    public List<Issue> toIssues(JsonNode body) {
        List<Issue> issues = new ArrayList<>();
        int skipped = 0;
        for (JsonNode node : body.path("risk")) {
            String policyId = DatastoreRowMapper.text(node, "policy_id");
            if (!DatastoreParams.isPolicyId(policyId)) {
                skipped++;
                continue;
            }
            issues.add(new Issue(
                    policyId,
                    DatastoreRowMapper.text(node, "risk"),
                    upper(DatastoreRowMapper.text(node, "source")),
                    upper(DatastoreRowMapper.text(node, "severity")),
                    DatastoreRowMapper.text(node, "description"),
                    DatastoreRowMapper.text(node, "remediation"),
                    blankToNull(DatastoreRowMapper.text(node, "remediation_recommendation_code_cli")),
                    webUrl(DatastoreRowMapper.text(node, "learn_more")),
                    trimmed(DatastoreRowMapper.text(node.path("compliance"), "name"))));
        }
        if (skipped > 0) {
            log.info("Skipped {} misconfiguration findings without a usable policy id", skipped);
        }
        return issues;
    }

    /** An asset row's {@code scan_status.last_scanned_at} when it is a number, else null. */
    public static Long lastScannedAt(JsonNode assetNode) {
        JsonNode value = assetNode.path("scan_status").path("last_scanned_at");
        return value.isNumber() ? value.asLong() : null;
    }

    // ── the project ──────────────────────────────────────────────────────────────────────────────

    /**
     * Groups the findings by policy id. A policy's text fields come from its first finding in source
     * order; its frameworks are the distinct names over all its findings, first seen first; a datastore
     * is affected at most once per policy, in source order. Policies are sorted by severity (CRITICAL,
     * HIGH, MEDIUM, LOW, other), then affected datastores descending, then title ignoring case, then id.
     */
    public MisconfigurationSnapshot toSnapshot(List<Source> sources, int datastoresRead, boolean capped) {
        Map<String, PolicyBuilder> policies = new LinkedHashMap<>();
        for (Source source : sources) {
            for (Issue issue : source.issues()) {
                policies.computeIfAbsent(issue.policyId(), id -> new PolicyBuilder(issue)).add(issue, source);
            }
        }
        List<MisconfigurationDetail> details = new ArrayList<>(policies.size());
        for (PolicyBuilder builder : policies.values()) {
            details.add(builder.build());
        }
        details.sort(Comparator
                .comparingInt((MisconfigurationDetail d) -> severityRank(d.severity()))
                .thenComparing(d -> d.affected().size(), Comparator.reverseOrder())
                .thenComparing(d -> d.title() == null ? "" : d.title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MisconfigurationDetail::policyId));
        return new MisconfigurationSnapshot(datastoresRead, capped, details);
    }

    /** The page: the two cards and one row per policy, in the snapshot's order. */
    public MisconfigurationList toList(MisconfigurationSnapshot snapshot) {
        Set<String> affectedDatastores = new HashSet<>();
        long[] bySeverity = new long[SEVERITY_ORDER.size()];
        List<MisconfigurationList.Row> rows = new ArrayList<>(snapshot.policies().size());
        for (MisconfigurationDetail policy : snapshot.policies()) {
            int rank = severityRank(policy.severity());
            if (rank < bySeverity.length) {
                bySeverity[rank]++;
            }
            long sensitiveRecords = 0L;
            Set<String> labels = new LinkedHashSet<>();
            Set<String> cloudAccounts = new LinkedHashSet<>();
            for (MisconfigurationDetail.AffectedDatastore affected : policy.affected()) {
                DatastoreRow row = affected.datastore();
                if (row.sourceId() != null) {
                    affectedDatastores.add(row.sourceId());
                }
                if (row.sensitives() != null) {
                    sensitiveRecords += row.sensitives().recordCount();
                    if (row.sensitives().typeLabels() != null) {
                        labels.addAll(row.sensitives().typeLabels());
                    }
                }
                if (CLOUD.equals(row.category()) && row.source() != null && row.source().accountLabel() != null) {
                    cloudAccounts.add(row.source().accountLabel());
                }
            }
            rows.add(new MisconfigurationList.Row(policy.policyId(), policy.title(), policy.source(),
                    policy.severity(), policy.severityLabel(), policy.affected().size(), sensitiveRecords,
                    List.copyOf(labels), policy.frameworks(), List.copyOf(cloudAccounts)));
        }
        MisconfigurationList.SeverityCounts open = new MisconfigurationList.SeverityCounts(
                snapshot.policies().size(), bySeverity[0], bySeverity[1], bySeverity[2], bySeverity[3]);
        return new MisconfigurationList(affectedDatastores.size(), open, snapshot.datastoresRead(),
                snapshot.capped(), rows);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** One policy being grouped. */
    private static final class PolicyBuilder {
        private final Issue first;
        private final Set<String> frameworks = new LinkedHashSet<>();
        private final Set<Object> seen = new HashSet<>();
        private final List<MisconfigurationDetail.AffectedDatastore> affected = new ArrayList<>();

        PolicyBuilder(Issue first) {
            this.first = first;
        }

        void add(Issue issue, Source source) {
            if (issue.framework() != null) {
                frameworks.add(issue.framework());
            }
            Object datastore = source.row().sourceId() != null ? source.row().sourceId() : source;
            if (seen.add(datastore)) {
                affected.add(new MisconfigurationDetail.AffectedDatastore(source.row(), source.lastScannedAt()));
            }
        }

        MisconfigurationDetail build() {
            return new MisconfigurationDetail(first.policyId(), first.title(), first.source(), first.severity(),
                    DatastoreLabels.misconfigurationSeverityLabel(first.severity()), first.description(),
                    first.remediation(), first.cli(), first.learnMore(), List.copyOf(frameworks),
                    List.copyOf(affected));
        }
    }

    /** 0 for CRITICAL to 3 for LOW; 4 for any other severity, null included. */
    static int severityRank(String severity) {
        int rank = severity == null ? -1 : SEVERITY_ORDER.indexOf(severity);
        return rank < 0 ? SEVERITY_ORDER.size() : rank;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The URL when it is absolute with scheme http or https, else null. */
    private static String webUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute() && scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null ? value.trim() : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
