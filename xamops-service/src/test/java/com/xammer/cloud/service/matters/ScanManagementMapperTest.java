package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.scans.ScanRun;
import com.xammer.cloud.dto.matters.scans.ScanRunDatastore;
import com.xammer.cloud.dto.matters.scans.ScanRunDetail;
import com.xammer.cloud.dto.matters.scans.ScanSchedule;
import com.xammer.cloud.dto.matters.scans.ScanScheduleDetail;
import com.xammer.cloud.dto.matters.scans.ScanScope;
import com.xammer.cloud.dto.matters.scans.ScanScopeDetail;
import com.xammer.cloud.dto.matters.scans.ScanTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Design 5.6.2 on synthetic bodies shaped like the scan-management routes. Fields that must not be read
 * carry {@code MARKER}, and the serialised DTOs are checked for it.
 */
class ScanManagementMapperTest {

    private static final String MARKER = "MARKER";
    private static final String HEX = "abcdef0123456789abcdef01";
    private static final String RUN_ID = "0000000a-0000-4000-8000-00000000000b";

    private final ObjectMapper json = new ObjectMapper();
    private final ScanManagementMapper mapper = new ScanManagementMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SCHEDULE = "{'id': '" + HEX + "', 'name': null, 'platform': null,"
            + "'coverage': 'SELECTED_DATASTORES', 'total_datastores': 12, 'total_is_estimate': false,"
            + "'state': 'ACTIVE', 'is_legacy': true, 'last_run_at': null, 'last_run_status': 'NOT_STARTED',"
            + "'next_run_at': 1700000100, 'created_at': 1700000000,"
            + "'created_by': {'email': 'MARKER@example.test', 'id': 'MARKER', 'name': 'MARKER'},"
            + "'schedule': {'type': 'RECURRING', 'trigger_time': 1700000000000, 'recurrence_type': 'DAILY',"
            + "  'repeat_on': ['MON'], 'day_of_month': 3, 'end_date': null, 'timezone': 'UTC'},"
            + "'recurrence': {'type': 'WEEKLY', 'repeat_on': ['TUE', 'THU'], 'day_of_month': null},"
            + "'scope_mode': null}";

    @Test
    @DisplayName("schedules: fields, labels, timing with recurrence preferred, paging from pagination")
    void schedules() {
        DspmPage<ScanSchedule> page = mapper.toSchedules(parse("{'data': [" + SCHEDULE + "],"
                + "'pagination': {'page': 9, 'page_size': 99, 'total': 31, 'total_pages': 4}}"), 2, 10);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(31);
        assertThat(page.totalPages()).isEqualTo(4);
        ScanSchedule schedule = page.data().get(0);
        assertThat(schedule.id()).isEqualTo(HEX);
        assertThat(schedule.name()).isNull();
        assertThat(schedule.platformLabel()).isNull();
        assertThat(schedule.coverageLabel()).isEqualTo("Selected datastores");
        assertThat(schedule.totalDatastores()).isEqualTo(12);
        assertThat(schedule.totalIsEstimate()).isFalse();
        assertThat(schedule.state()).isEqualTo("ACTIVE");
        assertThat(schedule.legacy()).isTrue();
        assertThat(schedule.lastRunStatusLabel()).isEqualTo("Not started");
        assertThat(schedule.nextRunAt()).isEqualTo(1_700_000_100L);
        assertThat(schedule.timing()).isEqualTo(new ScanSchedule.Timing("RECURRING", 1_700_000_000L, "WEEKLY",
                List.of("TUE", "THU"), 3, null, "UTC"));
        assertThat(serialise(page)).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("schedules: without recurrence the schedule's own fields are used; no pagination counts the rows")
    void schedulesFallbacks() {
        DspmPage<ScanSchedule> page = mapper.toSchedules(parse("{'data': [{'id': 'x',"
                + "'schedule': {'type': 'ONCE', 'recurrence_type': 'MONTHLY', 'repeat_on': 'MON', 'day_of_month': 5},"
                + "'recurrence': {'type': null, 'repeat_on': null, 'day_of_month': null}}, 'not an object']}"), 1, 10);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.data().get(0).timing()).isEqualTo(new ScanSchedule.Timing("ONCE", null, "MONTHLY",
                List.of("MON"), 5, null, null));
        assertThat(mapper.toSchedules(parse("{}"), 1, 10).data()).isEmpty();
    }

    @Test
    @DisplayName("schedule detail: scope mode, scope names, targets without source_id, and the targets total")
    void scheduleDetail() {
        String body = SCHEDULE.substring(0, SCHEDULE.length() - 1)
                + ", 'scope_mode': 'SELECTED', 'scope': {'asset_ids': ['MARKER']}, 'scope_ids': ['MARKER'],"
                + "'scopes': [{'name': 'Prod buckets', 'id': 'MARKER'}, {'name': null}],"
                + "'selected_assets': [{'id': 'a1', 'name': 'bucket-a', 'subtype': 'S3', 'source_id': 'MARKER'}],"
                + "'selected_assets_pagination': {'total': 40}}";
        ScanScheduleDetail detail = mapper.toSchedule(parse(body));
        assertThat(detail.schedule().id()).isEqualTo(HEX);
        assertThat(detail.scopeMode()).isEqualTo("SELECTED");
        assertThat(detail.scopeNames()).containsExactly("Prod buckets");
        assertThat(detail.targets()).containsExactly(new ScanTarget("a1", "bucket-a", "S3", "S3"));
        assertThat(detail.targetsTotal()).isEqualTo(40);
        assertThat(serialise(detail)).doesNotContain(MARKER);

        ScanScheduleDetail bare = mapper.toSchedule(parse("{'id': 'x', 'selected_assets': [{'id': 'a'}, {'id': 'b'}]}"));
        assertThat(bare.targetsTotal()).isEqualTo(2);
        assertThat(bare.scopeNames()).isEmpty();
    }

    private static final String RUN = "{'id': '" + RUN_ID + "', 'schedule_name': 'Nightly',"
            + "'coverage': 'SELECTED_DATASTORES', 'platform': 'endpoint', 'trigger': 'IMMEDIATE',"
            + "'scheduled_time': 1700000000, 'started_at': 1700000010, 'ended_at': 1700000070,"
            + "'duration_seconds': 60, 'status': 'SUCCESS', 'phase': 'COMPLETED',"
            + "'status_counts': {'SUCCESS': 3, 'FAILED': 1, 'NOTE': 'x'},"
            + "'completed_datastores': 3, 'total_datastores': 4, 'skipped_count': 1,"
            + "'created_by': {'email': 'MARKER'}, 'schedule_id': 'MARKER', 'scope_ids': ['MARKER'],"
            + "'trigger_window': {'start': 'MARKER'}}";

    @Test
    @DisplayName("runs: fields, labels, numeric status counts")
    void runs() {
        DspmPage<ScanRun> page = mapper.toRuns(parse("{'data': [" + RUN + "], 'pagination': {'total': 8}}"), 1, 5);
        assertThat(page.total()).isEqualTo(8);
        assertThat(page.totalPages()).isEqualTo(2);
        ScanRun run = page.data().get(0);
        assertThat(run.name()).isEqualTo("Nightly");
        assertThat(run.platformLabel()).isEqualTo("Endpoint");
        assertThat(run.triggerLabel()).isEqualTo("Immediate");
        assertThat(run.statusLabel()).isEqualTo("Success");
        assertThat(run.phaseLabel()).isEqualTo("Completed");
        assertThat(run.durationSeconds()).isEqualTo(60);
        assertThat(run.statusCounts()).containsExactly(entry("SUCCESS", 3L), entry("FAILED", 1L));
        assertThat(run.completedDatastores()).isEqualTo(3);
        assertThat(run.totalDatastores()).isEqualTo(4);
        assertThat(run.skippedCount()).isEqualTo(1);
        assertThat(serialise(page)).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("run detail: scope names, skipped examples and the account facet in both shapes")
    void runDetail() {
        String body = RUN.substring(0, RUN.length() - 1) + ", 'scopes': [{'name': 'All laptops'}],"
                + "'skipped_examples': ['laptop-x', {'name': 'laptop-y', 'id': 'MARKER'}, 4],"
                + "'facets': {'account': ['acct-a', {'value': 'acct-b', 'count': 2}, {'name': 'acct-c'}, {}]}}";
        ScanRunDetail detail = mapper.toRun(parse(body));
        assertThat(detail.run().id()).isEqualTo(RUN_ID);
        assertThat(detail.scopeNames()).containsExactly("All laptops");
        assertThat(detail.skippedExamples()).containsExactly("laptop-x", "laptop-y");
        assertThat(detail.accounts()).containsExactly(
                new ScanRunDetail.FacetValue("acct-a", null),
                new ScanRunDetail.FacetValue("acct-b", 2L),
                new ScanRunDetail.FacetValue("acct-c", null));
        assertThat(serialise(detail)).doesNotContain(MARKER);

        ScanRunDetail empty = mapper.toRun(parse("{'id': 'x', 'facets': {'account': []}}"));
        assertThat(empty.accounts()).isEmpty();
        assertThat(empty.run().totalDatastores()).isZero();
    }

    @Test
    @DisplayName("run datastores: fields and labels, pg_id not read, an over-long failure reason dropped")
    void runDatastores() {
        DspmPage<ScanRunDatastore> page = mapper.toRunDatastores(parse("{'data': ["
                + "{'id': 'd1', 'name': 'laptop-a', 'subtype': 'MAC', 'account_name': null, 'region': null,"
                + " 'status': 'FAILED', 'failure_reason': 'Agent offline', 'pg_id': 'MARKER'},"
                + "{'id': 'd2', 'status': 'SUCCESS', 'failure_reason': '" + "x".repeat(1_001) + "'}],"
                + "'pagination': {'total': 2, 'total_pages': 1}}"), 1, 10);
        assertThat(page.data()).containsExactly(
                new ScanRunDatastore("d1", "laptop-a", "MAC", "macOS", null, null, "FAILED", "Failed", "Agent offline"),
                new ScanRunDatastore("d2", null, null, null, null, null, "SUCCESS", "Success", null));
        assertThat(serialise(page)).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("scopes and scope detail")
    void scopes() {
        String scope = "{'id': 's1', 'name': 'Prod', 'platform': 'on_prem', 'scope_mode': 'FILTERS',"
                + "'total_datastores': 3, 'updated_at': '2026-01-02T03:04:05Z', 'created_by': {'name': 'MARKER'}";
        DspmPage<ScanScope> page = mapper.toScopes(parse("{'data': [" + scope + "}], 'pagination': {'total': 1}}"),
                1, 10);
        assertThat(page.data()).containsExactly(
                new ScanScope("s1", "Prod", "on_prem", "On-Prem", "FILTERS", 3L, 1_767_323_045L));

        ScanScopeDetail detail = mapper.toScope(parse(scope + ", 'selected_assets': [{'id': 'p1', 'name': 'pg-a',"
                + " 'subtype': 'SELF_HOSTED_POSTGRES', 'source_id': 'MARKER'}], 'selected_assets_pagination': {}}"));
        assertThat(detail.scope().name()).isEqualTo("Prod");
        assertThat(detail.targets()).containsExactly(new ScanTarget("p1", "pg-a", "SELF_HOSTED_POSTGRES", "PostgreSQL"));
        assertThat(detail.targetsTotal()).isEqualTo(1);
        assertThat(serialise(page) + serialise(detail)).doesNotContain(MARKER);
    }
}
