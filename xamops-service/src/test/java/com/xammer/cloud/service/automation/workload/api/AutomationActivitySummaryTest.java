package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.JpaSliceTestConfig;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.AutomationActivityService.Feature;
import com.xammer.cloud.service.automation.workload.api.AutomationActivityService.Query;
import com.xammer.cloud.service.automation.workload.api.AutomationActivityService.Result;
import com.xammer.cloud.service.automation.workload.api.dto.ActivityEntryDto;
import com.xammer.cloud.service.automation.workload.api.dto.ActivitySummaryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-day summary (FEAT-005) against a real (H2) table: India Standard Time days, the feature
 * filter, counts that are exactly the feed's entries by result, tenancy, no day listed before the
 * activity log could count it, and the scan cap.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class AutomationActivitySummaryTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    /** 11:30 on 19 Oct in India Standard Time, so a 7-day window is 13 Oct to 19 Oct. */
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");

    /** A resize from before the activity log: submitted targets and the observed sizes, as arrays. */
    private static final String LEGACY_AFTER = "[{\"container\":\"encoder\",\"cpuRequestMillis\":10}]";

    @Autowired
    WorkloadVerticalActionRepository repository;

    @Test
    @DisplayName("a day is an India Standard Time day: 18:29 and 18:31 UTC fall on two days, and the window starts at IST midnight")
    void daysAreIndiaStandardTimeDays() {
        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-10T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-12T18:29:59Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-12T18:30:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-17T18:29:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-17T18:31:00Z");

        ActivitySummaryDto summary = service().summary(TARGET, Set.of(), 7);

        assertThat(summary.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(summary.from()).isEqualTo("2026-10-13");
        assertThat(summary.to()).isEqualTo("2026-10-19");
        assertThat(summary.engine().enabled()).isTrue();
        assertThat(summary.truncated()).isFalse();
        assertThat(summary.firstRecordedAt()).isEqualTo(Instant.parse("2026-10-10T04:00:00Z"));
        assertThat(summary.completeSince()).isEqualTo(Instant.parse("2026-10-10T04:00:00Z"));
        assertThat(dates(summary)).containsExactly("2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16",
                "2026-10-17", "2026-10-18", "2026-10-19");
        assertThat(day(summary, "2026-10-13").applied())
                .as("00:00 IST on 13 Oct is the window's first instant; 23:59:59 IST on 12 Oct is outside it")
                .isEqualTo(1);
        assertThat(day(summary, "2026-10-17").applied()).as("18:29 UTC is 23:59 IST").isEqualTo(1);
        assertThat(day(summary, "2026-10-18").applied()).as("18:31 UTC is 00:01 IST the next day").isEqualTo(1);
        assertThat(day(summary, "2026-10-15"))
                .as("a listed day with nothing on it is a real zero")
                .isEqualTo(new ActivitySummaryDto.Day("2026-10-15", 0, 0, 0, 0, 0, day(summary, "2026-10-15").byFeature()));
        assertThat(day(summary, "2026-10-15").byFeature().get("HPA"))
                .isEqualTo(new ActivitySummaryDto.Counts(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("the feature filter counts one feature, and byFeature splits the two")
    void theFeatureFilterSplitsTheCounts() {
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-15T05:00:00Z");
        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-15T06:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "APPLIED", false, bounds(), "2026-10-16T05:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "TEST_RUN", true, bounds(), "2026-10-16T06:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "EXPIRED", false, bounds(), "2026-10-17T05:00:00Z");

        ActivitySummaryDto both = service().summary(TARGET, Set.of(), 7);
        assertThat(dates(both)).startsWith("2026-10-15").endsWith("2026-10-19");
        ActivitySummaryDto.Day fifteenth = day(both, "2026-10-15");
        assertThat(fifteenth.byFeature()).containsOnlyKeys("VERTICAL", "HPA");
        assertThat(fifteenth.byFeature().keySet()).containsExactly("VERTICAL", "HPA");
        assertThat(fifteenth.applied()).isEqualTo(1);
        assertThat(fifteenth.testRuns()).isEqualTo(1);
        assertThat(fifteenth.byFeature().get("VERTICAL")).isEqualTo(new ActivitySummaryDto.Counts(1, 0, 0, 1, 0));
        assertThat(fifteenth.byFeature().get("HPA")).isEqualTo(new ActivitySummaryDto.Counts(0, 0, 0, 0, 0));
        assertThat(day(both, "2026-10-16").byFeature().get("HPA")).isEqualTo(new ActivitySummaryDto.Counts(1, 0, 0, 1, 0));
        assertThat(day(both, "2026-10-17").failed()).as("an expired HPA command is a failure").isEqualTo(1);

        ActivitySummaryDto hpa = service().summary(TARGET, Set.of(Feature.HPA), 7);
        assertThat(hpa.firstRecordedAt())
                .as("the first HPA entry, so no HPA day is listed before it")
                .isEqualTo(Instant.parse("2026-10-16T05:00:00Z"));
        assertThat(dates(hpa)).startsWith("2026-10-16");
        assertThat(day(hpa, "2026-10-16").byFeature()).containsOnlyKeys("HPA");
        assertThat(day(hpa, "2026-10-16").applied()).isEqualTo(1);
        assertThat(day(hpa, "2026-10-16").testRuns()).isEqualTo(1);
        assertThat(day(hpa, "2026-10-17").failed()).isEqualTo(1);

        ActivitySummaryDto vertical = service().summary(TARGET, Set.of(Feature.VERTICAL), 7);
        assertThat(day(vertical, "2026-10-15").byFeature()).containsOnlyKeys("VERTICAL");
        assertThat(day(vertical, "2026-10-16").applied()).as("the HPA's changes are not the vertical feature's").isZero();
        assertThat(day(vertical, "2026-10-17").failed()).isZero();
        assertThat(service().summary(TARGET, Set.of(Feature.VERTICAL, Feature.HPA), 7)).isEqualTo(both);
    }

    @Test
    @DisplayName("each day's counts are exactly the feed's entries of that day, by feature and result, over every kind of row")
    void theCountsAreTheFeedsResultMapping() {
        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-14T03:00:00Z");
        save(7L, 42L, "Deployment", "idle-batch-listener", "APPLIED", false, LEGACY_AFTER, "2026-10-14T18:29:00Z");
        save(7L, 42L, "Deployment", "throttled-encoder", "FAILED", false, LEGACY_AFTER, "2026-10-14T18:31:00Z");
        save(7L, 42L, "Deployment", "throttled-encoder", "FAILED", false, proof("DEFERRED"), "2026-10-15T04:00:00Z");
        save(7L, 42L, "Deployment", "throttled-encoder", "FAILED", false, proof("INFEASIBLE"), "2026-10-15T05:00:00Z");
        save(7L, 42L, "Deployment", "overprovisioned-api", "NOT_CHANGED", false, proof("FAILED"), "2026-10-15T06:00:00Z");
        save(7L, 42L, "Deployment", "overprovisioned-api", "REFUSED", false, null, "2026-10-16T04:00:00Z");
        save(7L, 42L, "Deployment", "overprovisioned-api", "REFUSED", true, null, "2026-10-16T05:00:00Z");
        save(7L, 42L, "Deployment", "overprovisioned-api", "POLICY_CHANGE", false, null, "2026-10-16T06:00:00Z");
        save(7L, 42L, "Deployment", "idle-batch-listener", "PARTIALLY_APPLIED", false, proof("PARTIALLY_APPLIED"),
                "2026-10-17T04:00:00Z");
        save(7L, 42L, "Deployment", "idle-batch-listener", "FAILED", false, proof("RESTART_DETECTED"),
                "2026-10-17T05:00:00Z");
        save(7L, 42L, "Deployment", "idle-batch-listener", "SOMETHING_NEW", false, null, "2026-10-17T06:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "APPLIED", false, bounds(), "2026-10-18T04:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "TEST_RUN", true, bounds(), "2026-10-18T05:00:00Z");
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "EXPIRED", false, bounds(), "2026-10-19T05:59:00Z");

        ActivitySummaryDto summary = service().summary(TARGET, Set.of(), 7);
        List<ActivityEntryDto> feed = service().page(TARGET,
                new Query(Set.of(), Set.of(), null, null, null, 200, null)).entries();

        assertThat(dates(summary)).containsExactly("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17",
                "2026-10-18", "2026-10-19");
        int counted = 0;
        for (ActivitySummaryDto.Day day : summary.days()) {
            for (Result result : Result.values()) {
                int total = 0;
                for (Feature feature : Feature.values()) {
                    int inFeed = (int) feed.stream()
                            .filter(entry -> feature.name().equals(entry.feature()))
                            .filter(entry -> result.name().equals(entry.result()))
                            .filter(entry -> istDate(entry.at()).equals(day.date()))
                            .count();
                    assertThat(countOf(day.byFeature().get(feature.name()), result))
                            .as("%s %s on %s", feature, result, day.date())
                            .isEqualTo(inFeed);
                    total += inFeed;
                }
                assertThat(countOf(day, result)).as("%s on %s", result, day.date()).isEqualTo(total);
                counted += total;
            }
        }
        int withResult = (int) feed.stream().filter(entry -> entry.result() != null).count();
        assertThat(withResult)
                .as("the mode change and the unknown outcome have no result")
                .isEqualTo(feed.size() - 2);
        assertThat(counted).as("every feed entry with a result is counted once").isEqualTo(withResult);
        assertThat(day(summary, "2026-10-14").applied()).isEqualTo(1);
        assertThat(day(summary, "2026-10-15").failed()).as("18:31 UTC on the 14th is the 15th in IST").isEqualTo(1);
        assertThat(day(summary, "2026-10-15").putBack()).isEqualTo(3);
        assertThat(day(summary, "2026-10-16").refused()).isEqualTo(2);
    }

    @Test
    @DisplayName("another tenant's rows, and the tenant's other cluster, are never counted and move no date")
    void otherTenantsAreNeverCounted() {
        save(99L, 42L, "Deployment", "foreign", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-13T04:00:00Z");
        save(99L, 42L, "Deployment", "foreign", "APPLIED", false, proof("APPLIED"), "2026-10-18T04:00:00Z");
        save(7L, 43L, "Deployment", "other-cluster", "APPLIED", false, proof("APPLIED"), "2026-10-18T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, LEGACY_AFTER, "2026-10-15T04:00:00Z");

        ActivitySummaryDto legacyOnly = service().summary(TARGET, Set.of(), 7);
        assertThat(legacyOnly.firstRecordedAt()).isEqualTo(Instant.parse("2026-10-15T04:00:00Z"));
        assertThat(legacyOnly.completeSince())
                .as("another tenant's full-log row says nothing about this tenant's cluster")
                .isNull();
        assertThat(legacyOnly.days()).isEmpty();

        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-18T06:00:00Z");
        ActivitySummaryDto summary = service().summary(TARGET, Set.of(), 7);
        assertThat(summary.completeSince()).isEqualTo(Instant.parse("2026-10-18T06:00:00Z"));
        assertThat(dates(summary)).containsExactly("2026-10-18", "2026-10-19");
        assertThat(day(summary, "2026-10-18")).isEqualTo(new ActivitySummaryDto.Day("2026-10-18", 0, 0, 0, 1, 0,
                day(summary, "2026-10-18").byFeature()));

        ClusterTarget otherCluster = new ClusterTarget(7L, 43L, "102227938259", "xamops-test-2", "ap-south-1");
        assertThat(service().summary(otherCluster, Set.of(), 7).completeSince()).isEqualTo(Instant.parse("2026-10-18T04:00:00Z"));
        assertThat(day(service().summary(otherCluster, Set.of(), 7), "2026-10-18").applied()).isEqualTo(1);
    }

    @Test
    @DisplayName("no day is listed before the full activity log: older rows are in the feed, never zero-filled days")
    void noDayIsListedBeforeTheFullLog() {
        assertThat(service().summary(TARGET, Set.of(), 30).days())
                .as("nothing recorded yet")
                .isEmpty();
        assertThat(service().summary(TARGET, Set.of(), 30).firstRecordedAt()).isNull();

        // What an older build left: a resize that reached a pod, a mode change, and a refusal, which an
        // older build could write too and so is no proof the full log was running.
        save(7L, 42L, "Deployment", "api", "APPLIED", false, LEGACY_AFTER, "2026-10-15T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "POLICY_CHANGE", false, null, "2026-10-16T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "REFUSED", false, null, "2026-10-16T05:00:00Z");

        ActivitySummaryDto older = service().summary(TARGET, Set.of(), 7);
        assertThat(older.firstRecordedAt()).isEqualTo(Instant.parse("2026-10-15T04:00:00Z"));
        assertThat(older.completeSince()).isNull();
        assertThat(older.days())
                .as("test runs, put-backs and HPA changes were not recorded on those days, so none is listed")
                .isEmpty();

        save(7L, 42L, "Deployment", "api", "NOT_CHANGED", false, proof("DEFERRED"), "2026-10-18T04:00:00Z");
        ActivitySummaryDto full = service().summary(TARGET, Set.of(), 7);
        assertThat(full.completeSince()).isEqualTo(Instant.parse("2026-10-18T04:00:00Z"));
        assertThat(dates(full)).containsExactly("2026-10-18", "2026-10-19");
        assertThat(day(full, "2026-10-18").putBack()).isEqualTo(1);

        // A resize stored with its proof object, and an HPA row, each mark the full log as well.
        repository.deleteAll();
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-17T04:00:00Z");
        assertThat(service().summary(TARGET, Set.of(), 7).completeSince()).isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
        repository.deleteAll();
        save(7L, 42L, "HorizontalPodAutoscaler", "php-apache", "APPLIED", false, bounds(), "2026-10-16T04:00:00Z");
        assertThat(service().summary(TARGET, Set.of(), 7).completeSince()).isEqualTo(Instant.parse("2026-10-16T04:00:00Z"));
    }

    @Test
    @DisplayName("past the scan cap, the day the cap cut into and every day before it are left out, and truncated is set")
    void theScanIsCapped() {
        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-01T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-16T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-17T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-17T05:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-18T04:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-18T05:00:00Z");
        save(7L, 42L, "Deployment", "api", "APPLIED", false, proof("APPLIED"), "2026-10-19T04:00:00Z");

        ActivitySummaryDto capped = service(4).summary(TARGET, Set.of(), 7);
        assertThat(capped.truncated()).isTrue();
        assertThat(dates(capped))
                .as("the four newest rows reach into the 17th, where an uncounted row remains")
                .containsExactly("2026-10-18", "2026-10-19");
        assertThat(day(capped, "2026-10-18").applied()).isEqualTo(2);
        assertThat(day(capped, "2026-10-19").applied()).isEqualTo(1);

        ActivitySummaryDto exact = service(3).summary(TARGET, Set.of(), 7);
        assertThat(exact.truncated()).isTrue();
        assertThat(dates(exact))
                .as("the three newest rows end the 18th whole: the first row past the cap is on the 17th")
                .containsExactly("2026-10-18", "2026-10-19");
        assertThat(day(exact, "2026-10-18").applied()).isEqualTo(2);

        ActivitySummaryDto whole = service(6).summary(TARGET, Set.of(), 7);
        assertThat(whole.truncated()).isFalse();
        assertThat(dates(whole)).startsWith("2026-10-13").endsWith("2026-10-19");
        assertThat(day(whole, "2026-10-16").applied()).isEqualTo(1);
    }

    @Test
    @DisplayName("days is 1..30; one day is today alone")
    void theWindowIsBounded() {
        save(7L, 42L, "Deployment", "api", "TEST_RUN", true, proof("DRY_RUN"), "2026-10-01T04:00:00Z");

        ActivitySummaryDto today = service().summary(TARGET, Set.of(), 1);
        assertThat(today.from()).isEqualTo("2026-10-19");
        assertThat(dates(today)).containsExactly("2026-10-19");
        assertThat(service().summary(TARGET, Set.of(), 30).from()).isEqualTo("2026-09-20");
        assertThatThrownBy(() -> service().summary(TARGET, Set.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().summary(TARGET, Set.of(), 31)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the summary reads no further into after_json than it fetches")
    void thePrefixTheSummaryReadsIsLongEnough() {
        for (String prefix : AutomationActivityService.PUT_BACK_TERMINAL_PREFIXES) {
            assertThat(prefix.length()).isLessThanOrEqualTo(WorkloadVerticalActionRepository.SUMMARY_AFTER_JSON_PREFIX);
        }
        assertThat(AutomationActivityService.PROOF_OBJECT_PREFIX.length())
                .isLessThanOrEqualTo(WorkloadVerticalActionRepository.SUMMARY_AFTER_JSON_PREFIX);
    }

    // ---------------------------------------------------------------- fixtures

    private AutomationActivityService service() {
        return service(AutomationActivityService.MAX_SUMMARY_ROWS);
    }

    private AutomationActivityService service(int maxSummaryRows) {
        return new AutomationActivityService(repository, new WorkloadAutoscalingProperties(true, false),
                Clock.fixed(NOW, ZoneOffset.UTC), maxSummaryRows);
    }

    private void save(Long clientId, Long clusterConfigId, String kind, String name, String outcome, boolean dryRun,
                      String afterJson, String appliedAt) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(clientId);
        row.setClusterConfigId(clusterConfigId);
        row.setNamespace(kind.equals("HorizontalPodAutoscaler") ? "team-b" : "prod-sim");
        row.setWorkloadKind(kind);
        row.setWorkloadName(name);
        row.setTriggerSource("MANUAL");
        row.setDryRun(dryRun);
        row.setOutcome(outcome);
        row.setRestartFree(false);
        row.setAfterJson(afterJson);
        row.setAppliedAt(Instant.parse(appliedAt));
        repository.saveAndFlush(row);
    }

    /** A newer row's after_json, in the recorder's shape: terminal first. */
    private static String proof(String terminal) {
        return "{\"terminal\":\"" + terminal + "\",\"reverted\":null,\"containers\":[{\"container\":\"encoder\","
                + "\"observedBefore\":{\"cpuRequestMillis\":10},\"observedAfter\":{\"cpuRequestMillis\":10}}],"
                + "\"pods\":[]}";
    }

    private static String bounds() {
        return "{\"minReplicas\":1,\"maxReplicas\":8,\"targetUtilizationPct\":50}";
    }

    private static List<String> dates(ActivitySummaryDto summary) {
        return summary.days().stream().map(ActivitySummaryDto.Day::date).toList();
    }

    private static ActivitySummaryDto.Day day(ActivitySummaryDto summary, String date) {
        return summary.days().stream().filter(day -> day.date().equals(date)).findFirst()
                .orElseThrow(() -> new AssertionError("no day " + date + " in " + dates(summary)));
    }

    private static String istDate(Instant at) {
        return LocalDate.ofInstant(at, AutomationActivityService.SUMMARY_ZONE).toString();
    }

    private static int countOf(ActivitySummaryDto.Day day, Result result) {
        return countOf(new ActivitySummaryDto.Counts(day.applied(), day.putBack(), day.refused(), day.testRuns(),
                day.failed()), result);
    }

    private static int countOf(ActivitySummaryDto.Counts counts, Result result) {
        return switch (result) {
            case APPLIED -> counts.applied();
            case PUT_BACK -> counts.putBack();
            case REFUSED -> counts.refused();
            case TEST_RUN -> counts.testRuns();
            case FAILED -> counts.failed();
        };
    }
}
