package com.xammer.cloud.service.automation.workload.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository.ActivityCursor;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository.SummaryRow;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.ActivityEntryDto;
import com.xammer.cloud.service.automation.workload.api.dto.ActivityPageDto;
import com.xammer.cloud.service.automation.workload.api.dto.ActivitySummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.afterJsonStartsWithAny;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.afterJsonStartsWithNone;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.appliedBetween;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.dryRun;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.kindIs;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.kindIsNot;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.nameContains;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.nameIs;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.namespaceIs;
import static com.xammer.cloud.repository.WorkloadVerticalActionRepository.outcomeIn;
import static com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.KIND_HPA;
import static com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.OUTCOME_NOT_CHANGED;
import static com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.OUTCOME_POLICY_CHANGE;
import static com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.OUTCOME_REFUSED;
import static com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder.OUTCOME_TEST_RUN;

/**
 * The activity feed: what XamOps changed, tested or was refused on one cluster, read from
 * {@code vertical_workload_action}, newest first.
 *
 * <h2>Results</h2>
 *
 * Each row maps to one result, and the database filter uses the same rule
 * ({@link #resultOf} and {@link #resultIs} are kept side by side for that reason):
 *
 * <ul>
 *   <li>{@code REFUSED}: outcome {@code REFUSED};</li>
 *   <li>{@code TEST_RUN}: outcome {@code TEST_RUN}, or any other dry-run row;</li>
 *   <li>{@code PUT_BACK}: outcome {@code NOT_CHANGED}, or a row written since the activity log whose
 *       terminal is {@code DEFERRED} or {@code INFEASIBLE};</li>
 *   <li>{@code APPLIED}: outcome {@code APPLIED} or {@code PARTIALLY_APPLIED};</li>
 *   <li>{@code FAILED}: outcome {@code FAILED} or {@code EXPIRED}. A row from before the activity log
 *       holds only the published outcome, so an old deferral reads as {@code FAILED} with its stored
 *       detail: the executor builds that detail inline, not from a constant, so it is not parsed.</li>
 * </ul>
 *
 * A mode change ({@code POLICY_CHANGE}) is not an apply and has no result; it is listed under the
 * Workload autoscaler with the action {@code MODE_CHANGE}.
 *
 * <h2>Both {@code after_json} shapes</h2>
 *
 * Rows from before the activity log hold an array of the sizes observed afterwards and, in
 * {@code before_json}, the submitted targets; their "before" is that request and is labelled so.
 * Newer rows hold the full proof object. Anything that does not parse is left out (null), never
 * guessed.
 *
 * <h2>The per-day summary</h2>
 *
 * {@link #summary} counts the same rows per India Standard Time day with the same mapping
 * ({@link #featureOf} and {@link #resultOf}), so a day's counts are always the feed's entries of that
 * day by result. It lists a day only once every kind of attempt was being recorded on the cluster
 * ({@link #fullLogRow}); an earlier day is left out rather than shown with counts that miss what was
 * not recorded then.
 */
@Service
public class AutomationActivityService {

    private static final Logger logger = LoggerFactory.getLogger(AutomationActivityService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_QUERY_LENGTH = 100;
    public static final int MAX_CURSOR_LENGTH = 128;
    public static final Duration MAX_RANGE = Duration.ofDays(90);

    static final String WHO_USER = "A user";
    static final String WHO_AUTO = "XamOps (automatic)";

    /** The start of a newer row's {@code after_json} whose terminal is a deferral or infeasible resize. */
    static final List<String> PUT_BACK_TERMINAL_PREFIXES =
            List.of("{\"terminal\":\"DEFERRED\"", "{\"terminal\":\"INFEASIBLE\"");

    /**
     * The start of every resize's {@code after_json} the full activity log writes: the recorder's
     * proof object, terminal first. Rows from before it hold an array, or nothing.
     */
    static final String PROOF_OBJECT_PREFIX = "{\"terminal\":";

    /** The calendar the summary counts days in: India Standard Time, which keeps no daylight saving. */
    public static final ZoneId SUMMARY_ZONE = ZoneId.of("Asia/Kolkata");
    public static final int DEFAULT_SUMMARY_DAYS = 7;
    public static final int MAX_SUMMARY_DAYS = 30;
    /** The most rows one summary counts; past it the oldest days are left out and flagged. */
    public static final int MAX_SUMMARY_ROWS = 10_000;

    private static final Pattern MODE_CHANGE = Pattern.compile("^Mode changed from (\\S+) to (\\S+?)\\.?$");

    public enum Feature { VERTICAL, HPA }

    public enum Result { APPLIED, PUT_BACK, REFUSED, TEST_RUN, FAILED }

    public enum Action { RESIZE, HPA_CHANGE, MODE_CHANGE }

    /**
     * One page's filters, already validated.
     *
     * @param features     empty for every feature
     * @param results      empty for every row, including those without a result
     * @param nameContains a case-insensitive part of the workload or HPA name, or null
     * @param namespace    exactly this namespace, or null
     * @param kind         exactly this kind ({@code Deployment}, {@code HorizontalPodAutoscaler}, …), or null
     * @param name         exactly this workload or HPA name, or null
     * @param from         inclusive lower bound, or null
     * @param to           inclusive upper bound, or null
     * @param cursor       where the previous page ended, or null for the first page
     */
    public record Query(Set<Feature> features, Set<Result> results, String nameContains, String namespace,
                        String kind, String name, Instant from, Instant to, int limit, ActivityCursor cursor) {

        public Query {
            features = features == null ? Set.of() : Set.copyOf(features);
            results = results == null ? Set.of() : Set.copyOf(results);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + ", got " + limit);
            }
        }

        /** Without an exact namespace, kind or name. */
        public Query(Set<Feature> features, Set<Result> results, String nameContains, Instant from, Instant to,
                     int limit, ActivityCursor cursor) {
            this(features, results, nameContains, null, null, null, from, to, limit, cursor);
        }
    }

    private final WorkloadVerticalActionRepository repository;
    private final WorkloadAutoscalingProperties properties;
    /** The summary's only ambient input: which day is today. */
    private final Clock clock;
    private final int maxSummaryRows;

    @Autowired
    public AutomationActivityService(WorkloadVerticalActionRepository repository,
                                     WorkloadAutoscalingProperties properties) {
        this(repository, properties, Clock.systemUTC(), MAX_SUMMARY_ROWS);
    }

    /** @param maxSummaryRows the summary's scan cap; {@link #MAX_SUMMARY_ROWS} outside tests */
    AutomationActivityService(WorkloadVerticalActionRepository repository,
                              WorkloadAutoscalingProperties properties, Clock clock, int maxSummaryRows) {
        if (maxSummaryRows < 1) {
            throw new IllegalArgumentException("maxSummaryRows must be positive, got " + maxSummaryRows);
        }
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.maxSummaryRows = maxSummaryRows;
    }

    /**
     * One page for one tenant's registered cluster. Served whether or not the engine is on, since
     * the history is a record of the past; {@code engine.enabled} says which.
     */
    public ActivityPageDto page(ClusterTarget target, Query query) {
        List<WorkloadVerticalAction> rows = repository.findActivityPage(target.clientId(),
                target.clusterConfigId(), filter(query), query.cursor(), query.limit() + 1);
        boolean older = rows.size() > query.limit();
        List<WorkloadVerticalAction> shown = older ? rows.subList(0, query.limit()) : rows;
        String nextCursor = older ? encodeCursor(shown.get(shown.size() - 1)) : null;
        return new ActivityPageDto(new ActivityPageDto.Engine(properties.isEnabled()),
                shown.stream().map(AutomationActivityService::toEntry).toList(), nextCursor);
    }

    // ---------------------------------------------------------------- the per-day summary

    /**
     * What only the full activity log writes: a test run, an apply that changed no pod, an HPA row,
     * or a resize stored with its proof object. Before the first such row XamOps recorded only the
     * resizes that reached a pod, and mode changes, so a day before it would count test runs,
     * refusals, put-backs and HPA changes as zero when they were simply not recorded. A refusal is
     * not on the list because an older build could write that outcome too.
     */
    static Specification<WorkloadVerticalAction> fullLogRow() {
        return outcomeIn(List.of(OUTCOME_TEST_RUN, OUTCOME_NOT_CHANGED))
                .or(kindIs(KIND_HPA))
                .or(afterJsonStartsWithAny(List.of(PROOF_OBJECT_PREFIX)));
    }

    /**
     * Changes per India Standard Time day over the last {@code days} days, today included, counted
     * with the feed's own mapping, so each day's counts are the feed's entries of that day by result.
     * Mode changes, which have no result, are not counted.
     *
     * <p>A day is listed from the later of the first row of the selected features and the first
     * {@link #fullLogRow}, never before: an earlier day is left out, not zero-filled. Every day from
     * then to today is listed, and a day with nothing on it counts zero, because nothing happened.
     * At most {@link #MAX_SUMMARY_ROWS} rows are counted, newest first; past that the day of the
     * first row left uncounted, and every day before it, are left out and {@code truncated} is set.
     * Served whether or not the engine is on, like the feed.
     *
     * @param features empty for every feature
     * @param days     1..{@link #MAX_SUMMARY_DAYS}
     */
    public ActivitySummaryDto summary(ClusterTarget target, Set<Feature> features, int days) {
        if (days < 1 || days > MAX_SUMMARY_DAYS) {
            throw new IllegalArgumentException("days must be 1.." + MAX_SUMMARY_DAYS + ", got " + days);
        }
        Set<Feature> selected = features == null || features.isEmpty()
                ? EnumSet.allOf(Feature.class) : EnumSet.copyOf(features);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, SUMMARY_ZONE);
        LocalDate from = today.minusDays(days - 1L);

        Instant firstRecordedAt = repository.findFirstAppliedAt(target.clientId(), target.clusterConfigId(),
                featureFilter(selected)).orElse(null);
        Instant completeSince = repository.findFirstAppliedAt(target.clientId(), target.clusterConfigId(),
                fullLogRow()).orElse(null);
        List<SummaryRow> rows = repository.findSummaryRows(target.clientId(), target.clusterConfigId(),
                from.atStartOfDay(SUMMARY_ZONE).toInstant(), now, maxSummaryRows + 1);
        boolean truncated = rows.size() > maxSummaryRows;
        // The newest row past the cap: its day, and every day before it, are not counted in full.
        SummaryRow firstUncounted = truncated ? rows.get(maxSummaryRows) : null;
        if (truncated) {
            rows = rows.subList(0, maxSummaryRows);
        }

        List<ActivitySummaryDto.Day> listed = new ArrayList<>();
        if (firstRecordedAt != null && completeSince != null) {
            LocalDate firstDay = latest(from, day(firstRecordedAt), day(completeSince));
            if (firstUncounted != null) {
                firstDay = latest(firstDay, day(firstUncounted.appliedAt()).plusDays(1));
            }
            Map<LocalDate, Map<Feature, int[]>> counted = count(rows, selected, firstDay, today);
            for (LocalDate date = firstDay; !date.isAfter(today); date = date.plusDays(1)) {
                listed.add(toDay(date, selected, counted.getOrDefault(date, Map.of())));
            }
        }
        return new ActivitySummaryDto(new ActivityPageDto.Engine(properties.isEnabled()), SUMMARY_ZONE.getId(),
                from.toString(), today.toString(), firstRecordedAt, completeSince, truncated, listed);
    }

    /** Per day and feature, how many rows carry each {@link Result}, indexed by its ordinal. */
    private static Map<LocalDate, Map<Feature, int[]>> count(List<SummaryRow> rows, Set<Feature> selected,
                                                             LocalDate firstDay, LocalDate today) {
        Map<LocalDate, Map<Feature, int[]>> counted = new LinkedHashMap<>();
        for (SummaryRow row : rows) {
            Feature feature = featureOf(row.workloadKind());
            Result result = resultOf(row.outcome(), row.dryRun(), row.afterJsonStart());
            LocalDate date = day(row.appliedAt());
            if (!selected.contains(feature) || result == null || date.isBefore(firstDay) || date.isAfter(today)) {
                continue;
            }
            counted.computeIfAbsent(date, ignored -> new EnumMap<>(Feature.class))
                    .computeIfAbsent(feature, ignored -> new int[Result.values().length])[result.ordinal()]++;
        }
        return counted;
    }

    private static ActivitySummaryDto.Day toDay(LocalDate date, Set<Feature> selected, Map<Feature, int[]> byFeature) {
        int[] total = new int[Result.values().length];
        Map<String, ActivitySummaryDto.Counts> features = new LinkedHashMap<>();
        for (Feature feature : selected) {
            int[] ofFeature = byFeature.getOrDefault(feature, new int[Result.values().length]);
            for (int i = 0; i < total.length; i++) {
                total[i] += ofFeature[i];
            }
            features.put(feature.name(), counts(ofFeature));
        }
        ActivitySummaryDto.Counts sum = counts(total);
        return new ActivitySummaryDto.Day(date.toString(), sum.applied(), sum.putBack(), sum.refused(),
                sum.testRuns(), sum.failed(), features);
    }

    private static ActivitySummaryDto.Counts counts(int[] byResult) {
        return new ActivitySummaryDto.Counts(byResult[Result.APPLIED.ordinal()], byResult[Result.PUT_BACK.ordinal()],
                byResult[Result.REFUSED.ordinal()], byResult[Result.TEST_RUN.ordinal()],
                byResult[Result.FAILED.ordinal()]);
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, SUMMARY_ZONE);
    }

    private static LocalDate latest(LocalDate first, LocalDate... others) {
        LocalDate latest = first;
        for (LocalDate other : others) {
            if (other.isAfter(latest)) {
                latest = other;
            }
        }
        return latest;
    }

    // ---------------------------------------------------------------- filters

    static Specification<WorkloadVerticalAction> filter(Query query) {
        List<Specification<WorkloadVerticalAction>> parts = new ArrayList<>();
        if (query.from() != null || query.to() != null) {
            parts.add(appliedBetween(query.from(), query.to()));
        }
        Specification<WorkloadVerticalAction> features = featureFilter(query.features());
        if (features != null) {
            parts.add(features);
        }
        if (!query.results().isEmpty()) {
            parts.add(Specification.anyOf(query.results().stream()
                    .map(AutomationActivityService::resultIs).toList()));
        }
        if (query.nameContains() != null) {
            parts.add(nameContains(query.nameContains()));
        }
        if (query.namespace() != null) {
            parts.add(namespaceIs(query.namespace()));
        }
        if (query.kind() != null) {
            parts.add(kindIs(query.kind()));
        }
        if (query.name() != null) {
            parts.add(nameIs(query.name()));
        }
        return parts.isEmpty() ? null : Specification.allOf(parts);
    }

    /** The database form of {@link #featureOf}: null when every feature is selected (or none named). */
    static Specification<WorkloadVerticalAction> featureFilter(Set<Feature> features) {
        if (features == null || features.size() != 1) {
            return null;
        }
        return features.contains(Feature.HPA) ? kindIs(KIND_HPA) : kindIsNot(KIND_HPA);
    }

    /** An HPA row is the HPA's; every other row, mode changes included, the Workload autoscaler's. */
    static Feature featureOf(String workloadKind) {
        return KIND_HPA.equals(workloadKind) ? Feature.HPA : Feature.VERTICAL;
    }

    /** The database form of {@link #resultOf}: the same rule, as column predicates. */
    static Specification<WorkloadVerticalAction> resultIs(Result result) {
        return switch (result) {
            case REFUSED -> outcomeIn(List.of(OUTCOME_REFUSED));
            case TEST_RUN -> Specification.not(outcomeIn(List.of(OUTCOME_REFUSED)))
                    .and(outcomeIn(List.of(OUTCOME_TEST_RUN)).or(dryRun(true)));
            case PUT_BACK -> dryRun(false)
                    .and(Specification.not(outcomeIn(List.of(OUTCOME_REFUSED, OUTCOME_TEST_RUN))))
                    .and(outcomeIn(List.of(OUTCOME_NOT_CHANGED))
                            .or(afterJsonStartsWithAny(PUT_BACK_TERMINAL_PREFIXES)));
            case APPLIED -> dryRun(false)
                    .and(outcomeIn(List.of("APPLIED", "PARTIALLY_APPLIED")))
                    .and(afterJsonStartsWithNone(PUT_BACK_TERMINAL_PREFIXES));
            case FAILED -> dryRun(false)
                    .and(outcomeIn(List.of("FAILED", "EXPIRED")))
                    .and(afterJsonStartsWithNone(PUT_BACK_TERMINAL_PREFIXES));
        };
    }

    /** One row's result; null for a mode change and for an outcome this build does not know. */
    static Result resultOf(WorkloadVerticalAction row) {
        return resultOf(row.getOutcome(), row.getDryRun(), row.getAfterJson());
    }

    /**
     * The one result mapping, which the feed and the summary both call.
     *
     * @param afterJson the row's {@code after_json}, or only its first
     *                  {@link WorkloadVerticalActionRepository#SUMMARY_AFTER_JSON_PREFIX} characters:
     *                  the rule reads no further than {@link #PUT_BACK_TERMINAL_PREFIXES}
     */
    static Result resultOf(String outcome, Boolean dryRun, String afterJson) {
        if (OUTCOME_REFUSED.equals(outcome)) {
            return Result.REFUSED;
        }
        if (OUTCOME_TEST_RUN.equals(outcome) || Boolean.TRUE.equals(dryRun)) {
            return Result.TEST_RUN;
        }
        if (OUTCOME_NOT_CHANGED.equals(outcome) || startsWithPutBackTerminal(afterJson)) {
            return Result.PUT_BACK;
        }
        if ("APPLIED".equals(outcome) || "PARTIALLY_APPLIED".equals(outcome)) {
            return Result.APPLIED;
        }
        if ("FAILED".equals(outcome) || "EXPIRED".equals(outcome)) {
            return Result.FAILED;
        }
        return null;
    }

    private static boolean startsWithPutBackTerminal(String afterJson) {
        return afterJson != null && PUT_BACK_TERMINAL_PREFIXES.stream().anyMatch(afterJson::startsWith);
    }

    // ---------------------------------------------------------------- the cursor

    /** Opaque to the caller: base64url of {@code <applied_at ISO-8601>|<id>}. */
    static String encodeCursor(WorkloadVerticalAction row) {
        String raw = row.getAppliedAt() + "|" + row.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the cursor is not one this service wrote */
    public static ActivityCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("cursor is empty or too long");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.lastIndexOf('|');
            if (bar <= 0 || bar == raw.length() - 1) {
                throw new IllegalArgumentException("cursor has no instant and id");
            }
            return new ActivityCursor(Instant.parse(raw.substring(0, bar)),
                    Long.parseLong(raw.substring(bar + 1)));
        } catch (DateTimeException unreadable) {
            throw new IllegalArgumentException("cursor holds no instant", unreadable);
        }
    }

    // ---------------------------------------------------------------- one entry

    static ActivityEntryDto toEntry(WorkloadVerticalAction row) {
        boolean hpa = featureOf(row.getWorkloadKind()) == Feature.HPA;
        boolean modeChange = OUTCOME_POLICY_CHANGE.equals(row.getOutcome());
        JsonNode before = parse(row.getBeforeJson(), row);
        JsonNode after = parse(row.getAfterJson(), row);
        // A row written since the activity log: after_json is the proof object, terminal first.
        boolean proofShape = !hpa && after != null && after.isObject() && after.has("terminal");

        ActivityEntryDto.Change change;
        if (hpa) {
            change = hpaChange(before, after);
        } else if (modeChange) {
            change = modeChange(row.getDetail());
        } else {
            change = containerChange(before, after, proofShape);
        }

        Action action = modeChange ? Action.MODE_CHANGE : hpa ? Action.HPA_CHANGE : Action.RESIZE;
        Result result = resultOf(row);
        String origin = "AUTO".equals(row.getTriggerSource()) ? "AUTO"
                : "MANUAL".equals(row.getTriggerSource()) ? "MANUAL" : null;
        String who = "AUTO".equals(origin) ? WHO_AUTO : "MANUAL".equals(origin) ? WHO_USER : null;

        return new ActivityEntryDto(row.getId(), row.getAppliedAt(),
                hpa ? Feature.HPA.name() : Feature.VERTICAL.name(), origin, who, action.name(),
                new ActivityEntryDto.Target(row.getNamespace(), row.getWorkloadKind(), row.getWorkloadName()),
                Boolean.TRUE.equals(row.getDryRun()), row.getOutcome(),
                result == null ? null : result.name(),
                proofShape ? text(after.get("terminal")) : null,
                Boolean.TRUE.equals(row.getRestartFree()), change,
                proofShape ? proof(row, after) : null,
                row.getDetail(), row.getCorrelationId());
    }

    private static JsonNode parse(String json, WorkloadVerticalAction row) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception unreadable) {
            logger.debug("Activity row {} holds JSON that does not parse; it is shown without it",
                    row.getId());
            return null;
        }
    }

    /**
     * Per container: the submitted target ({@code before_json}) and the observed sizes. A newer row
     * observed both sides on the pod; an older one observed only "after", so its "before" is the
     * request, labelled {@code REQUESTED}. A container nobody asked to change and whose size did not
     * move is left out: it was not part of the action.
     */
    private static ActivityEntryDto.Change containerChange(JsonNode before, JsonNode after,
                                                           boolean proofShape) {
        Map<String, ActivityEntryDto.Resources> requested = resourcesByContainer(before);
        List<ActivityEntryDto.ContainerChange> containers = new ArrayList<>();
        if (proofShape) {
            Map<String, JsonNode> observed = new LinkedHashMap<>();
            JsonNode list = after.get("containers");
            if (list != null && list.isArray()) {
                for (JsonNode container : list) {
                    String name = text(container.get("container"));
                    if (name != null) {
                        observed.put(name, container);
                    }
                }
            }
            Set<String> names = new LinkedHashSet<>(requested.keySet());
            names.addAll(observed.keySet());
            for (String name : names) {
                JsonNode seen = observed.get(name);
                ActivityEntryDto.Resources was = seen == null ? null : resources(seen.get("observedBefore"));
                ActivityEntryDto.Resources now = seen == null ? null : resources(seen.get("observedAfter"));
                if (!requested.containsKey(name) && (was == null || was.equals(now))) {
                    continue;
                }
                containers.add(new ActivityEntryDto.ContainerChange(name, was, was == null ? null : "OBSERVED",
                        now, requested.get(name)));
            }
        } else {
            Map<String, ActivityEntryDto.Resources> observedAfter = resourcesByContainer(after);
            Set<String> names = new LinkedHashSet<>(requested.keySet());
            names.addAll(observedAfter.keySet());
            for (String name : names) {
                ActivityEntryDto.Resources asked = requested.get(name);
                containers.add(new ActivityEntryDto.ContainerChange(name, asked,
                        asked == null ? null : "REQUESTED", observedAfter.get(name), asked));
            }
        }
        return containers.isEmpty() ? null : new ActivityEntryDto.Change(containers, null, null);
    }

    /** An array of {@code {container, cpuRequestMillis, …}} by container name; anything else is empty. */
    private static Map<String, ActivityEntryDto.Resources> resourcesByContainer(JsonNode array) {
        Map<String, ActivityEntryDto.Resources> byName = new LinkedHashMap<>();
        if (array == null || !array.isArray()) {
            return byName;
        }
        for (JsonNode container : array) {
            String name = text(container.get("container"));
            if (name != null) {
                byName.put(name, resources(container));
            }
        }
        return byName;
    }

    private static ActivityEntryDto.Resources resources(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ActivityEntryDto.Resources(integer(node.get("cpuRequestMillis")),
                integer(node.get("cpuLimitMillis")), longValue(node.get("memRequestBytes")),
                longValue(node.get("memLimitBytes")));
    }

    private static ActivityEntryDto.Change hpaChange(JsonNode before, JsonNode after) {
        ActivityEntryDto.HpaBounds was = bounds(before);
        ActivityEntryDto.HpaBounds now = bounds(after);
        if (was == null && now == null) {
            return null;
        }
        return new ActivityEntryDto.Change(null, new ActivityEntryDto.HpaChange(was, now), null);
    }

    private static ActivityEntryDto.HpaBounds bounds(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ActivityEntryDto.HpaBounds(integer(node.get("minReplicas")),
                integer(node.get("maxReplicas")), integer(node.get("targetUtilizationPct")));
    }

    /** "Mode changed from RECOMMEND to AUTO." as {@code {from, to}}; anything else is null. */
    private static ActivityEntryDto.Change modeChange(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher matcher = MODE_CHANGE.matcher(detail.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ActivityEntryDto.Change(null, null,
                new ActivityEntryDto.ModeChange(matcher.group(1), matcher.group(2)));
    }

    /** The per-pod evidence of a newer row; null when it touched no pod (a test run, a refusal). */
    private static ActivityEntryDto.Proof proof(WorkloadVerticalAction row, JsonNode after) {
        JsonNode list = after.get("pods");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        List<ActivityEntryDto.PodProof> pods = new ArrayList<>();
        for (JsonNode pod : list) {
            String name = text(pod.get("pod"));
            if (name == null) {
                continue;
            }
            JsonNode revert = pod.get("revert");
            pods.add(new ActivityEntryDto.PodProof(name, text(pod.get("node")), bool(pod.get("changed")),
                    bool(pod.get("uidChanged")),
                    integer(pod.get("restartsBefore")), integer(pod.get("restartsAfter")),
                    bool(pod.get("ready")),
                    revert == null || !revert.isObject() ? null
                            : new ActivityEntryDto.Revert(text(revert.get("state")), text(revert.get("detail")))));
        }
        return pods.isEmpty() ? null
                : new ActivityEntryDto.Proof(Boolean.TRUE.equals(row.getRestartFree()), bool(after.get("reverted")),
                pods);
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private static Integer integer(JsonNode node) {
        return node == null || !node.isIntegralNumber() || !node.canConvertToInt() ? null : node.asInt();
    }

    private static Long longValue(JsonNode node) {
        return node == null || !node.isIntegralNumber() || !node.canConvertToLong() ? null : node.asLong();
    }

    private static Boolean bool(JsonNode node) {
        return node == null || !node.isBoolean() ? null : node.asBoolean();
    }
}
