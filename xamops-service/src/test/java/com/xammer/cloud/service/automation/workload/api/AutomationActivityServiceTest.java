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
import com.xammer.cloud.service.automation.workload.api.dto.ActivityPageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The feed's result vocabulary against a real (H2) table: the database filter and the per-row
 * mapping must agree on every kind of row the table holds — the rows from before the activity log,
 * the rows it adds, mode changes and HPA rows — and the cursor must walk every row exactly once.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class AutomationActivityServiceTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");

    /** The rows from before the activity log, as UAT holds them (submitted targets; observed after). */
    static final String LEGACY_BEFORE = "[{\"container\":\"encoder\",\"cpuRequestMillis\":63}]";
    static final String LEGACY_AFTER = "[{\"container\":\"encoder\",\"cpuRequestMillis\":10,\"cpuLimitMillis\":100,"
            + "\"memRequestBytes\":67108864,\"memLimitBytes\":134217728}]";
    static final String LEGACY_DEFERRAL_DETAIL = "The kubelet deferred the resize of pod"
            + " prod-sim/throttled-encoder-mcw8q because its node has no room for it right now (cpu: requested 63m,"
            + " used 1900m, capacity 1930m).";

    @Autowired
    WorkloadVerticalActionRepository repository;

    private AutomationActivityService service;
    /** Every saved row with the result the mapping gives it, in insertion order. */
    private final Map<Long, Result> expected = new LinkedHashMap<>();
    private final List<Long> hpaRows = new ArrayList<>();
    private int minute;

    @BeforeEach
    void setUp() {
        service = new AutomationActivityService(repository, new WorkloadAutoscalingProperties(false, false));
        expected.clear();
        hpaRows.clear();
        minute = 0;

        save("Deployment", "idle-batch-listener", "APPLIED", false, LEGACY_BEFORE, LEGACY_AFTER, Result.APPLIED);
        save("Deployment", "throttled-encoder", "FAILED", false, LEGACY_BEFORE, LEGACY_AFTER, Result.FAILED);
        save("Deployment", "throttled-encoder", "FAILED", false, LEGACY_BEFORE,
                proof("DEFERRED", false), Result.PUT_BACK);
        save("Deployment", "throttled-encoder", "FAILED", false, LEGACY_BEFORE,
                proof("INFEASIBLE", false), Result.PUT_BACK);
        save("Deployment", "overprovisioned-api", "NOT_CHANGED", false, LEGACY_BEFORE,
                proof("DEFERRED", true), Result.PUT_BACK);
        save("Deployment", "overprovisioned-api", "NOT_CHANGED", false, LEGACY_BEFORE,
                proof("FAILED", null), Result.PUT_BACK);
        save("Deployment", "overprovisioned-api", "TEST_RUN", true, LEGACY_BEFORE,
                proof("DRY_RUN", null), Result.TEST_RUN);
        save("Deployment", "overprovisioned-api", "REFUSED", false, null, null, Result.REFUSED);
        save("Deployment", "overprovisioned-api", "REFUSED", true, null, null, Result.REFUSED);
        save("Deployment", "overprovisioned-api", "POLICY_CHANGE", false, null, null, null);
        save("Deployment", "idle-batch-listener", "PARTIALLY_APPLIED", false, LEGACY_BEFORE,
                proof("PARTIALLY_APPLIED", null), Result.APPLIED);
        save("Deployment", "idle-batch-listener", "FAILED", false, LEGACY_BEFORE,
                proof("RESTART_DETECTED", null), Result.FAILED);
        save("Deployment", "idle-batch-listener", "SOMETHING_NEW", false, null, null, null);
        hpaRows.add(save("HorizontalPodAutoscaler", "php-apache", "APPLIED", false, bounds(1, 5), bounds(1, 8),
                Result.APPLIED));
        hpaRows.add(save("HorizontalPodAutoscaler", "php-apache", "TEST_RUN", true, bounds(1, 5), bounds(1, 8),
                Result.TEST_RUN));
        hpaRows.add(save("HorizontalPodAutoscaler", "php-apache", "EXPIRED", false, bounds(1, 5), bounds(1, 8),
                Result.FAILED));
        // Another tenant's row on the same config id, which no query may return.
        WorkloadVerticalAction foreign = row("Deployment", "foreign", "APPLIED", false, null, null);
        foreign.setClientId(99L);
        repository.saveAndFlush(foreign);
    }

    @Test
    @DisplayName("the database filter and the per-row mapping agree for every result, over every kind of row")
    void theFilterAndTheMappingAgree() {
        for (Result result : Result.values()) {
            List<Long> filtered = ids(service.page(TARGET, query(Set.of(), Set.of(result), null, 200)));
            List<Long> mapped = newestFirst(expected.entrySet().stream()
                    .filter(entry -> entry.getValue() == result).map(Map.Entry::getKey).toList());

            assertThat(filtered).as("result %s", result).isEqualTo(mapped).isNotEmpty();
            for (WorkloadVerticalAction row : repository.findAllById(filtered)) {
                assertThat(AutomationActivityService.resultOf(row)).as("row %s", row.getId()).isEqualTo(result);
            }
        }
        // Two results are their union.
        assertThat(ids(service.page(TARGET, query(Set.of(), Set.of(Result.REFUSED, Result.TEST_RUN), null, 200))))
                .hasSize(4);
        // No result filter: every row of the tenant, including a mode change and an unknown outcome.
        assertThat(ids(service.page(TARGET, query(Set.of(), Set.of(), null, 200))))
                .isEqualTo(newestFirst(new ArrayList<>(expected.keySet())));
    }

    @Test
    @DisplayName("the feature filter splits HPA rows from the Workload autoscaler's, mode changes included")
    void theFeatureFilterSplitsTheRows() {
        List<Long> hpa = ids(service.page(TARGET, query(Set.of(Feature.HPA), Set.of(), null, 200)));
        List<Long> vertical = ids(service.page(TARGET, query(Set.of(Feature.VERTICAL), Set.of(), null, 200)));

        assertThat(hpa).isEqualTo(newestFirst(hpaRows));
        assertThat(vertical).hasSize(expected.size() - hpaRows.size()).doesNotContainAnyElementsOf(hpaRows);
        assertThat(ids(service.page(TARGET, query(EnumSet.allOf(Feature.class), Set.of(), null, 200))))
                .hasSize(expected.size());
        assertThat(ids(service.page(TARGET, query(Set.of(Feature.HPA), Set.of(Result.FAILED), null, 200))))
                .containsExactly(hpaRows.get(2));
    }

    @Test
    @DisplayName("name search is case-insensitive over the workload or HPA name")
    void theNameSearchMatchesPartOfAName() {
        assertThat(ids(service.page(TARGET, query(Set.of(), Set.of(), "ENCODER", 200)))).hasSize(3);
        assertThat(ids(service.page(TARGET, query(Set.of(), Set.of(), "php", 200)))).hasSize(3);
        assertThat(ids(service.page(TARGET, query(Set.of(), Set.of(), "foreign", 200))))
                .as("another tenant's row is never found")
                .isEmpty();
    }

    @Test
    @DisplayName("the cursor walks every row exactly once, newest first, and the last page has none")
    void theCursorWalksEveryRowOnce() {
        List<Long> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ActivityPageDto page = service.page(TARGET, new Query(Set.of(), Set.of(), null, null, null, 4,
                    cursor == null ? null : AutomationActivityService.decodeCursor(cursor)));
            walked.addAll(ids(page));
            cursor = page.nextCursor();
            assertThat(cursor == null || cursor.length() <= AutomationActivityService.MAX_CURSOR_LENGTH).isTrue();
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(walked).isEqualTo(newestFirst(new ArrayList<>(expected.keySet())));
        assertThat(pages).isEqualTo((expected.size() + 3) / 4);
    }

    @Test
    @DisplayName("the history is served with the engine off, and says the engine is off")
    void theHistoryIsServedWithTheEngineOff() {
        ActivityPageDto page = service.page(TARGET, query(Set.of(), Set.of(), null, 1));

        assertThat(page.engine().enabled()).isFalse();
        assertThat(page.entries()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("a cursor this service did not write is refused")
    void aForeignCursorIsRefused() {
        assertThatThrownBy(() -> AutomationActivityService.decodeCursor("not-base64!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationActivityService.decodeCursor("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        for (String raw : List.of("no-bar", "2026-10-12T00:00:00Z|", "|12", "yesterday|12",
                "2026-10-12T00:00:00Z|twelve")) {
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> AutomationActivityService.decodeCursor(encoded))
                    .as(raw).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AutomationActivityService.decodeCursor(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-10-12T00:00:00Z|12".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo(new WorkloadVerticalActionRepository.ActivityCursor(START, 12L));
    }

    // ---------------------------------------------------------------- one entry, both after_json shapes

    @Test
    @DisplayName("a legacy row: before is the request, labelled REQUESTED; after is what was observed; no proof")
    void aLegacyRowReadsItsArrays() {
        WorkloadVerticalAction legacy = row("Deployment", "throttled-encoder", "FAILED", false, LEGACY_BEFORE,
                LEGACY_AFTER);
        legacy.setDetail(LEGACY_DEFERRAL_DETAIL);

        ActivityEntryDto entry = AutomationActivityService.toEntry(legacy);

        assertThat(entry.result()).as("an old deferral reads as Failed, with its stored detail").isEqualTo("FAILED");
        assertThat(entry.terminal()).isNull();
        assertThat(entry.proof()).isNull();
        assertThat(entry.detail()).isEqualTo(LEGACY_DEFERRAL_DETAIL);
        ActivityEntryDto.ContainerChange container = entry.change().containers().get(0);
        assertThat(container.container()).isEqualTo("encoder");
        assertThat(container.beforeBasis()).isEqualTo("REQUESTED");
        assertThat(container.before()).isEqualTo(new ActivityEntryDto.Resources(63, null, null, null));
        assertThat(container.requested()).isEqualTo(container.before());
        assertThat(container.after()).isEqualTo(new ActivityEntryDto.Resources(10, 100, 67_108_864L, 134_217_728L));
    }

    @Test
    @DisplayName("a newer row: observed before and after, the request, the terminal and the per-pod proof")
    void aNewRowReadsTheProofObject() {
        WorkloadVerticalAction putBack = row("Deployment", "overprovisioned-api", "NOT_CHANGED", false,
                LEGACY_BEFORE, proof("DEFERRED", true));

        ActivityEntryDto entry = AutomationActivityService.toEntry(putBack);

        assertThat(entry.result()).isEqualTo("PUT_BACK");
        assertThat(entry.terminal()).isEqualTo("DEFERRED");
        ActivityEntryDto.ContainerChange container = entry.change().containers().get(0);
        assertThat(container.beforeBasis()).isEqualTo("OBSERVED");
        assertThat(container.before()).isEqualTo(new ActivityEntryDto.Resources(10, 100, 67_108_864L, 134_217_728L));
        assertThat(container.after()).isEqualTo(container.before());
        assertThat(container.requested()).isEqualTo(new ActivityEntryDto.Resources(63, null, null, null));
        assertThat(entry.proof().reverted()).isTrue();
        ActivityEntryDto.PodProof pod = entry.proof().pods().get(0);
        assertThat(pod.pod()).isEqualTo("overprovisioned-api-7c9d");
        assertThat(pod.node()).as("never guessed").isNull();
        assertThat(pod.uidChanged()).isFalse();
        assertThat(pod.restartsBefore()).isZero();
        assertThat(pod.restartsAfter()).isZero();
        assertThat(pod.ready()).isTrue();
        assertThat(pod.revert()).isEqualTo(new ActivityEntryDto.Revert("REVERTED", "Put back."));
    }

    @Test
    @DisplayName("a test run lists the request with no observed sizes and no proof")
    void aTestRunHasOnlyTheRequest() {
        ActivityEntryDto entry = AutomationActivityService.toEntry(row("Deployment", "overprovisioned-api",
                "TEST_RUN", true, LEGACY_BEFORE, proof("DRY_RUN", null, false)));

        assertThat(entry.result()).isEqualTo("TEST_RUN");
        assertThat(entry.dryRun()).isTrue();
        assertThat(entry.proof()).isNull();
        ActivityEntryDto.ContainerChange container = entry.change().containers().get(0);
        assertThat(container.before()).isNull();
        assertThat(container.beforeBasis()).isNull();
        assertThat(container.after()).isNull();
        assertThat(container.requested()).isEqualTo(new ActivityEntryDto.Resources(63, null, null, null));
    }

    @Test
    @DisplayName("an HPA row, a mode change, the trigger and anything unreadable map without guessing")
    void theOtherRowKindsMap() {
        WorkloadVerticalAction hpa = row("HorizontalPodAutoscaler", "php-apache", "APPLIED", false, bounds(1, 5),
                bounds(1, 8));
        hpa.setCorrelationId("corr-1");
        ActivityEntryDto hpaEntry = AutomationActivityService.toEntry(hpa);
        assertThat(hpaEntry.feature()).isEqualTo("HPA");
        assertThat(hpaEntry.action()).isEqualTo("HPA_CHANGE");
        assertThat(hpaEntry.change().hpa().before()).isEqualTo(new ActivityEntryDto.HpaBounds(1, 5, 50));
        assertThat(hpaEntry.change().hpa().after()).isEqualTo(new ActivityEntryDto.HpaBounds(1, 8, 50));
        assertThat(hpaEntry.terminal()).isNull();
        assertThat(hpaEntry.reference()).isEqualTo("corr-1");
        assertThat(hpaEntry.who()).isEqualTo("A user");

        WorkloadVerticalAction mode = row("Deployment", "api", "POLICY_CHANGE", false, null, null);
        mode.setDetail("Mode changed from RECOMMEND to AUTO.");
        mode.setTriggerSource("AUTO");
        ActivityEntryDto modeEntry = AutomationActivityService.toEntry(mode);
        assertThat(modeEntry.feature()).isEqualTo("VERTICAL");
        assertThat(modeEntry.action()).isEqualTo("MODE_CHANGE");
        assertThat(modeEntry.result()).as("a mode change is not an apply").isNull();
        assertThat(modeEntry.change().mode()).isEqualTo(new ActivityEntryDto.ModeChange("RECOMMEND", "AUTO"));
        assertThat(modeEntry.origin()).isEqualTo("AUTO");
        assertThat(modeEntry.who()).isEqualTo("XamOps (automatic)");

        WorkloadVerticalAction unreadable = row("Deployment", "api", "APPLIED", false, "{not json", "[1,");
        unreadable.setTriggerSource("SOMEONE");
        ActivityEntryDto unreadableEntry = AutomationActivityService.toEntry(unreadable);
        assertThat(unreadableEntry.change()).isNull();
        assertThat(unreadableEntry.proof()).isNull();
        assertThat(unreadableEntry.origin()).isNull();
        assertThat(unreadableEntry.who()).isNull();
        assertThat(unreadableEntry.result()).isEqualTo("APPLIED");
    }

    // ---------------------------------------------------------------- fixtures

    private Long save(String kind, String name, String outcome, boolean dryRun, String before, String after,
                      Result result) {
        WorkloadVerticalAction saved = repository.saveAndFlush(row(kind, name, outcome, dryRun, before, after));
        expected.put(saved.getId(), result);
        return saved.getId();
    }

    private WorkloadVerticalAction row(String kind, String name, String outcome, boolean dryRun, String before,
                                       String after) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(7L);
        row.setClusterConfigId(42L);
        row.setNamespace(kind.equals("HorizontalPodAutoscaler") ? "team-b" : "prod-sim");
        row.setWorkloadKind(kind);
        row.setWorkloadName(name);
        row.setTriggerSource("MANUAL");
        row.setDryRun(dryRun);
        row.setOutcome(outcome);
        row.setRestartFree(false);
        row.setBeforeJson(before);
        row.setAfterJson(after);
        row.setAppliedAt(START.plusSeconds(60L * minute++));
        return row;
    }

    /** Ids, newest first: rows are saved one minute apart, so the newest is the last saved. */
    private static List<Long> newestFirst(List<Long> ids) {
        List<Long> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static List<Long> ids(ActivityPageDto page) {
        return page.entries().stream().map(ActivityEntryDto::id).filter(Objects::nonNull).toList();
    }

    private static Query query(Set<Feature> features, Set<Result> results, String q, int limit) {
        return new Query(features, results, q, null, null, limit, null);
    }

    private static String bounds(int min, int max) {
        return "{\"minReplicas\":" + min + ",\"maxReplicas\":" + max + ",\"targetUtilizationPct\":50}";
    }

    private static String proof(String terminal, Boolean reverted) {
        return proof(terminal, reverted, true);
    }

    /** A newer row's after_json, in the recorder's own shape. */
    static String proof(String terminal, Boolean reverted, boolean withPod) {
        String revert = reverted == null ? "null"
                : "{\"state\":\"" + (reverted ? "REVERTED" : "NOT_CONFIRMED") + "\",\"detail\":\""
                + (reverted ? "Put back." : "Not confirmed.") + "\"}";
        String size = "{\"cpuRequestMillis\":10,\"cpuLimitMillis\":100,\"memRequestBytes\":67108864,"
                + "\"memLimitBytes\":134217728}";
        String containers = withPod
                ? "[{\"container\":\"encoder\",\"observedBefore\":" + size + ",\"observedAfter\":" + size + "}]"
                : "[]";
        String pods = withPod
                ? "[{\"pod\":\"overprovisioned-api-7c9d\",\"node\":null,\"uidChanged\":false,\"restartsBefore\":0,"
                + "\"restartsAfter\":0,\"ready\":true,\"revert\":" + revert + "}]"
                : "[]";
        return "{\"terminal\":\"" + terminal + "\",\"reverted\":" + (reverted == null ? "null" : reverted)
                + ",\"containers\":" + containers + ",\"pods\":" + pods + "}";
    }
}
