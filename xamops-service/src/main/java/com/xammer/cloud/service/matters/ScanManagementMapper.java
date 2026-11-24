package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.scans.ScanRun;
import com.xammer.cloud.dto.matters.scans.ScanRunDatastore;
import com.xammer.cloud.dto.matters.scans.ScanRunDetail;
import com.xammer.cloud.dto.matters.scans.ScanSchedule;
import com.xammer.cloud.dto.matters.scans.ScanScheduleDetail;
import com.xammer.cloud.dto.matters.scans.ScanScope;
import com.xammer.cloud.dto.matters.scans.ScanScopeDetail;
import com.xammer.cloud.dto.matters.scans.ScanTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scan Management schedules, runs, run datastores and scopes (design 5.6.2). Bare {@code {data[],
 * pagination}} bodies. Never read: {@code created_by}, {@code created_at}, {@code scope}, {@code scope_ids},
 * {@code schedule_id}, {@code trigger_window}, {@code selected_assets[].source_id}, {@code pg_id}.
 */
@Component
public class ScanManagementMapper {

    static final int MAX_ROWS = 100;
    static final int MAX_SCOPE_NAMES = 50;
    static final int MAX_TARGETS = 100;
    static final int MAX_STATUS_COUNTS = 10;
    static final int MAX_SKIPPED_EXAMPLES = 20;
    static final int MAX_ACCOUNTS = 100;
    static final int MAX_REPEAT_ON = 31;
    static final int MAX_FAILURE_REASON = 1_000;
    private static final int MAX_NAME = 1_024;

    // ── schedules ────────────────────────────────────────────────────────────────────────────────

    public DspmPage<ScanSchedule> toSchedules(JsonNode body, int page, int pageSize) {
        return page(body, page, pageSize, ScanManagementMapper::schedule);
    }

    public ScanScheduleDetail toSchedule(JsonNode body) {
        List<ScanTarget> targets = targets(body.path("selected_assets"));
        return new ScanScheduleDetail(schedule(body), DrawerJson.string(body, "scope_mode"),
                scopeNames(body.path("scopes")), targets,
                DatastoreRowMapper.longOr(body.path("selected_assets_pagination"), "total", targets.size()));
    }

    private static ScanSchedule schedule(JsonNode node) {
        String platform = DrawerJson.string(node, "platform");
        String coverage = DrawerJson.string(node, "coverage");
        String lastRunStatus = DrawerJson.string(node, "last_run_status");
        return new ScanSchedule(
                DrawerJson.string(node, "id"),
                DrawerJson.string(node, "name"),
                platform,
                PageLabels.platform(platform),
                coverage,
                PageLabels.coverage(coverage),
                DatastoreRowMapper.longOrNull(node, "total_datastores"),
                flag(node, "total_is_estimate"),
                DrawerJson.string(node, "state"),
                flag(node, "is_legacy"),
                timing(node.path("schedule"), node.path("recurrence")),
                DrawerJson.epochSeconds(node.path("last_run_at")),
                lastRunStatus,
                PageLabels.runStatus(lastRunStatus),
                DrawerJson.epochSeconds(node.path("next_run_at")));
    }

    /** {@code schedule{…}}, with {@code recurrence{type, repeat_on, day_of_month}} preferred when set. */
    private static ScanSchedule.Timing timing(JsonNode schedule, JsonNode recurrence) {
        String recurrenceType = DrawerJson.string(recurrence, "type");
        if (recurrenceType == null) {
            recurrenceType = DrawerJson.string(schedule, "recurrence_type");
        }
        List<String> repeatOn = repeatOn(recurrence.path("repeat_on"));
        if (repeatOn.isEmpty()) {
            repeatOn = repeatOn(schedule.path("repeat_on"));
        }
        Integer dayOfMonth = intOrNull(recurrence.path("day_of_month"));
        if (dayOfMonth == null) {
            dayOfMonth = intOrNull(schedule.path("day_of_month"));
        }
        return new ScanSchedule.Timing(
                DrawerJson.string(schedule, "type"),
                DrawerJson.epochSeconds(schedule.path("trigger_time")),
                recurrenceType,
                repeatOn,
                dayOfMonth,
                DrawerJson.epochSeconds(schedule.path("end_date")),
                DrawerJson.string(schedule, "timezone"));
    }

    private static List<String> repeatOn(JsonNode node) {
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode element : node) {
                if (out.size() >= MAX_REPEAT_ON) {
                    break;
                }
                String text = element.isNumber() ? element.asText() : DrawerJson.stringValue(element);
                if (text != null) {
                    out.add(text);
                }
            }
            return out;
        }
        String single = DrawerJson.stringValue(node);
        return single == null ? new ArrayList<>() : new ArrayList<>(List.of(single));
    }

    // ── runs ─────────────────────────────────────────────────────────────────────────────────────

    public DspmPage<ScanRun> toRuns(JsonNode body, int page, int pageSize) {
        return page(body, page, pageSize, ScanManagementMapper::run);
    }

    public ScanRunDetail toRun(JsonNode body) {
        List<String> skipped = new ArrayList<>();
        for (JsonNode example : body.path("skipped_examples")) {
            if (skipped.size() >= MAX_SKIPPED_EXAMPLES) {
                break;
            }
            String text = example.isObject() ? DrawerJson.string(example, "name") : DrawerJson.stringValue(example);
            if (text != null) {
                skipped.add(text);
            }
        }
        List<ScanRunDetail.FacetValue> accounts = new ArrayList<>();
        for (JsonNode account : body.path("facets").path("account")) {
            if (accounts.size() >= MAX_ACCOUNTS) {
                break;
            }
            if (account.isObject()) {
                String value = DrawerJson.string(account, "value");
                if (value == null) {
                    value = DrawerJson.string(account, "name");
                }
                if (value != null) {
                    accounts.add(new ScanRunDetail.FacetValue(value, DatastoreRowMapper.longOrNull(account, "count")));
                }
            } else {
                String value = DrawerJson.stringValue(account);
                if (value != null) {
                    accounts.add(new ScanRunDetail.FacetValue(value, null));
                }
            }
        }
        return new ScanRunDetail(run(body), scopeNames(body.path("scopes")), skipped, accounts);
    }

    private static ScanRun run(JsonNode node) {
        String coverage = DrawerJson.string(node, "coverage");
        String platform = DrawerJson.string(node, "platform");
        String trigger = DrawerJson.string(node, "trigger");
        String status = DrawerJson.string(node, "status");
        String phase = DrawerJson.string(node, "phase");
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> counts = node.path("status_counts").fields();
        while (counts.hasNext() && statusCounts.size() < MAX_STATUS_COUNTS) {
            Map.Entry<String, JsonNode> entry = counts.next();
            if (entry.getValue().isNumber()) {
                statusCounts.put(entry.getKey(), entry.getValue().asLong());
            }
        }
        return new ScanRun(
                DrawerJson.string(node, "id"),
                DrawerJson.string(node, "schedule_name"),
                coverage,
                PageLabels.coverage(coverage),
                platform,
                PageLabels.platform(platform),
                trigger,
                PageLabels.trigger(trigger),
                DrawerJson.epochSeconds(node.path("scheduled_time")),
                DrawerJson.epochSeconds(node.path("started_at")),
                DrawerJson.epochSeconds(node.path("ended_at")),
                DatastoreRowMapper.longOrNull(node, "duration_seconds"),
                status,
                PageLabels.runStatus(status),
                phase,
                PageLabels.runPhase(phase),
                statusCounts,
                DatastoreRowMapper.longOr(node, "completed_datastores", 0L),
                DatastoreRowMapper.longOr(node, "total_datastores", 0L),
                DatastoreRowMapper.longOr(node, "skipped_count", 0L));
    }

    // ── run datastores ───────────────────────────────────────────────────────────────────────────

    public DspmPage<ScanRunDatastore> toRunDatastores(JsonNode body, int page, int pageSize) {
        return page(body, page, pageSize, node -> {
            String subtype = DrawerJson.string(node, "subtype");
            String status = DrawerJson.string(node, "status");
            String failure = DrawerJson.string(node, "failure_reason");
            return new ScanRunDatastore(
                    DrawerJson.string(node, "id"),
                    DrawerJson.string(node, "name"),
                    subtype,
                    DatastoreLabels.subtypeLabel(subtype),
                    DrawerJson.string(node, "account_name"),
                    DrawerJson.string(node, "region"),
                    status,
                    PageLabels.runStatus(status),
                    failure == null || failure.length() > MAX_FAILURE_REASON ? null : failure);
        });
    }

    // ── scopes ───────────────────────────────────────────────────────────────────────────────────

    public DspmPage<ScanScope> toScopes(JsonNode body, int page, int pageSize) {
        return page(body, page, pageSize, ScanManagementMapper::scope);
    }

    public ScanScopeDetail toScope(JsonNode body) {
        List<ScanTarget> targets = targets(body.path("selected_assets"));
        return new ScanScopeDetail(scope(body), targets,
                DatastoreRowMapper.longOr(body.path("selected_assets_pagination"), "total", targets.size()));
    }

    private static ScanScope scope(JsonNode node) {
        String platform = DrawerJson.string(node, "platform");
        return new ScanScope(
                DrawerJson.string(node, "id"),
                DrawerJson.string(node, "name"),
                platform,
                PageLabels.platform(platform),
                DrawerJson.string(node, "scope_mode"),
                DatastoreRowMapper.longOrNull(node, "total_datastores"),
                DrawerJson.epochSeconds(node.path("updated_at")));
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Rows of {@code data[]} [100] with the request's page and size; total from {@code pagination.total}
     * (else the row count), total pages from {@code pagination.total_pages} (else computed).
     */
    private static <T> DspmPage<T> page(JsonNode body, int page, int pageSize, Function<JsonNode, T> row) {
        List<T> rows = new ArrayList<>();
        for (JsonNode node : body.path("data")) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            if (node.isObject()) {
                rows.add(row.apply(node));
            }
        }
        JsonNode pagination = body.path("pagination");
        long total = DatastoreRowMapper.longOr(pagination, "total", rows.size());
        Long totalPages = DatastoreRowMapper.longOrNull(pagination, "total_pages");
        int pages = totalPages != null && totalPages >= 0 && totalPages <= Integer.MAX_VALUE
                ? totalPages.intValue() : DatastoreRowMapper.totalPages(total, pageSize);
        return new DspmPage<>(rows, page, pageSize, total, pages);
    }

    private static List<String> scopeNames(JsonNode scopes) {
        List<String> names = new ArrayList<>();
        for (JsonNode scope : scopes) {
            if (names.size() >= MAX_SCOPE_NAMES) {
                break;
            }
            String name = scope.isObject() ? DrawerJson.string(scope, "name") : null;
            if (name != null && name.length() <= MAX_NAME) {
                names.add(name);
            }
        }
        return names;
    }

    /** {@code selected_assets[]{id, name, subtype}} [100]; {@code source_id} is not read. */
    private static List<ScanTarget> targets(JsonNode assets) {
        List<ScanTarget> targets = new ArrayList<>();
        for (JsonNode asset : assets) {
            if (targets.size() >= MAX_TARGETS) {
                break;
            }
            if (!asset.isObject()) {
                continue;
            }
            String subtype = DrawerJson.string(asset, "subtype");
            targets.add(new ScanTarget(DrawerJson.string(asset, "id"), DrawerJson.string(asset, "name"), subtype,
                    DatastoreLabels.subtypeLabel(subtype)));
        }
        return targets;
    }

    /** True only for a JSON {@code true}. */
    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() && value.booleanValue();
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }
}
