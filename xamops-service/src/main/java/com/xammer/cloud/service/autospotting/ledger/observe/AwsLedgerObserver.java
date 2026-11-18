package com.xammer.cloud.service.autospotting.ledger.observe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsEstimatePriceService;
import com.xammer.cloud.service.autospotting.aws.AwsGroupStatusReader;
import com.xammer.cloud.service.autospotting.aws.AwsReadOnlyView;
import com.xammer.cloud.service.autospotting.aws.AwsViewSource;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ledger-observe:aws} (design-ledger.md §6.4, §2.1; plan-phase-17.md 17.2): every 5 minutes, under its
 * job lease, for every AWS group in a measured state it opens and closes {@code spot_usage_interval} rows
 * and writes {@code spot_group_capacity_sample} when desired, floor or original-share units change.
 *
 * <p>It reads through {@link AwsReadOnlyView} only ({@code DescribeAutoScalingGroups},
 * {@code DescribeScalingActivities}, {@code DescribeInstances}), never mutates the cloud and never writes
 * money. It is governed only by switch L ({@link LedgerGate}) and {@code scheduling.enabled}. A read that
 * fails for one group is counted ({@code autospotting.cloud.errors}) and leaves that group's rows as they
 * were; the next sweep tries again, and an instance that was not seen simply keeps {@code first_absent_at}
 * as the end's upper bound ({@link IntervalTracker}).
 *
 * <p>Deviation from §6.4: it makes its own {@code DescribeAutoScalingGroups} call instead of reusing the
 * reconciler's read, because the reconciler keeps only a capacity summary, not the instance list (recorded
 * in the phase evidence). It is one call per group per 5 minutes, plus {@code DescribeScalingActivities}
 * and {@code DescribeInstances} only when an instance left or arrived.
 */
@Component
public class AwsLedgerObserver {

    private static final Logger log = LoggerFactory.getLogger(AwsLedgerObserver.class);
    public static final String JOB = "ledger-observe:aws";
    /** States whose groups are measured (plan-phase-17.md 17.2). */
    public static final List<String> MEASURED_STATES = List.of("ACTIVE", "DEGRADED", "FALLBACK", "PAUSED",
            "APPLYING", "REVERTING", "LEGACY_UNCONFIRMED");
    static final int MAX_ACTIVITIES = 50;
    static final int DESCRIBE_BATCH = 100;
    static final Duration RECENT_ACTIVITY = Duration.ofHours(2);
    static final int KNOWN_DAYS = 3;
    private static final Pattern TERMINATING = Pattern.compile("Terminating EC2 instance: (i-[0-9a-f]+)");

    private final JobRunner jobRunner;
    private final LedgerGate gate;
    private final ObservationStore store;
    private final CloudAccountRepository accounts;
    private final AwsViewSource views;
    private final AwsGroupStatusReader statusReader;
    private final AutoSpottingMetrics metrics;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public AwsLedgerObserver(JobRunner jobRunner, LedgerGate gate, ObservationStore store,
                             CloudAccountRepository accounts, AwsViewSource views, AwsGroupStatusReader statusReader,
                             AutoSpottingMetrics metrics) {
        this(jobRunner, gate, store, accounts, views, statusReader, metrics, Clock.systemUTC());
    }

    public AwsLedgerObserver(JobRunner jobRunner, LedgerGate gate, ObservationStore store, CloudAccountRepository accounts,
                      AwsViewSource views, AwsGroupStatusReader statusReader, AutoSpottingMetrics metrics,
                      Clock clock) {
        this.jobRunner = jobRunner;
        this.gate = gate;
        this.store = store;
        this.accounts = accounts;
        this.views = views;
        this.statusReader = statusReader;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Every 5 minutes. */
    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void sweep() {
        jobRunner.runLeased(JOB, null, () -> {
            if (gate.awsWriterOn()) {
                observeAll();
            }
        });
    }

    /** One sweep over every measured AWS group; returns the number of groups observed without error. */
    public int observeAll() {
        int observed = 0;
        Map<Long, Optional<CloudAccount>> accountCache = new LinkedHashMap<>();
        for (ObservationStore.ObservedGroup group : store.measuredGroups(MEASURED_STATES)) {
            Optional<CloudAccount> account = accountCache.computeIfAbsent(group.cloudAccountId(), accounts::findById);
            if (account.isEmpty()) {
                continue;
            }
            try {
                if (observeGroup(account.get(), group)) {
                    observed++;
                }
            } catch (RuntimeException e) {
                metrics.cloudError(Provider.AWS, "ledger-observe", AwsErrors.classify(e).name());
                log.warn("ledger-observe skipped group {}: {}", group.id(), e.getClass().getSimpleName());
            }
        }
        return observed;
    }

    public boolean observeGroup(CloudAccount account, ObservationStore.ObservedGroup group) {
        int slash = group.groupKey().indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String region = group.groupKey().substring(0, slash);
        String name = group.groupKey().substring(slash + 1);
        AwsReadOnlyView view = views.view(account, region);
        Optional<AutoScalingGroup> described = statusReader.describeGroup(view, name);
        if (described.isEmpty()) {
            return false; // the reconciler owns the GONE decision; the observer writes nothing for a missing group
        }
        Instant now = clock.instant();
        AutoScalingGroup asg = described.get();
        Map<String, BigDecimal> weights = weights(asg);

        List<IntervalTracker.Open> open = store.openIntervals(group.id());
        Set<String> known = new HashSet<>(store.knownInstanceIds(group.id(), KNOWN_DAYS));
        Set<String> openIds = new HashSet<>();
        open.forEach(o -> openIds.add(o.instanceId()));

        Set<String> inGroup = new HashSet<>();
        for (var i : asg.instances()) {
            inGroup.add(i.instanceId());
        }

        // Details are read only for instances that need an interval or whose end needs a cause.
        Set<String> needDetail = new HashSet<>();
        for (String id : inGroup) {
            if (!known.contains(id)) {
                needDetail.add(id);
            }
        }
        Set<String> absentOpen = new HashSet<>(openIds);
        absentOpen.removeAll(inGroup);
        needDetail.addAll(absentOpen);

        Map<String, Activity> terminations = absentOpen.isEmpty() && inGroup.stream().allMatch(known::contains)
                ? Map.of() : terminations(view, name, now);
        for (String id : terminations.keySet()) {
            if (!known.contains(id) && !inGroup.contains(id)) {
                needDetail.add(id);
            }
        }
        Map<String, Instance> details = needDetail.isEmpty() ? Map.of() : describe(view, needDetail);

        Map<String, IntervalTracker.Present> present = new LinkedHashMap<>();
        for (String id : inGroup) {
            Instance inst = details.get(id);
            if (inst != null && !known.contains(id)) {
                present.put(id, toPresent(inst, weights));
            }
        }
        // Present instances that already have an interval need no detail: the tracker only bumps them.
        Map<String, IntervalTracker.Present> presentForTracker = new LinkedHashMap<>(present);
        for (String id : inGroup) {
            if (known.contains(id)) {
                presentForTracker.putIfAbsent(id, new IntervalTracker.Present(id, null, null, null, null, null, null));
            }
        }

        Map<String, IntervalTracker.Ending> endings = new LinkedHashMap<>();
        for (Map.Entry<String, Activity> e : terminations.entrySet()) {
            String id = e.getKey();
            if (inGroup.contains(id)) {
                continue; // still listed: not ended
            }
            Instance inst = details.get(id);
            String reason = inst == null || inst.stateReason() == null ? null : inst.stateReason().code();
            EndCauseClassifier.Cause cause = EndCauseClassifier.classify(reason, e.getValue().cause());
            Instant endAt = e.getValue().startTime();
            IntervalTracker.Present detail = inst == null || inst.launchTime() == null ? null : toPresent(inst, weights);
            endings.put(id, new IntervalTracker.Ending(id, endAt, cause, detail));
        }
        List<IntervalTracker.Action> actions = IntervalTracker.plan(now, open, known, presentForTracker, endings);
        ObservationStore.Sample sample = sampleIfChanged(group, asg, store.latestSample(group.id()));
        store.apply(group.id(), group.cloudAccountId(), now, actions, sample);
        return true;
    }

    // ------------------------------------------------------------------ capacity sample

    private ObservationStore.Sample sampleIfChanged(ObservationStore.ObservedGroup group, AutoScalingGroup asg,
                                                    Optional<ObservationStore.Sample> latest) {
        if (asg.desiredCapacity() == null) {
            return null;
        }
        BigDecimal desired = BigDecimal.valueOf(asg.desiredCapacity().longValue());
        BigDecimal floor = BigDecimal.ZERO;
        if (asg.mixedInstancesPolicy() != null && asg.mixedInstancesPolicy().instancesDistribution() != null
                && asg.mixedInstancesPolicy().instancesDistribution().onDemandBaseCapacity() != null) {
            floor = BigDecimal.valueOf(asg.mixedInstancesPolicy().instancesDistribution().onDemandBaseCapacity()
                    .longValue());
        }
        BigDecimal original = originalSpotUnits(group.snapshotPayload(), desired);
        ObservationStore.Sample next = new ObservationStore.Sample(desired.setScale(6, RoundingMode.UNNECESSARY),
                floor.setScale(6, RoundingMode.UNNECESSARY), original.setScale(6, RoundingMode.UNNECESSARY));
        if (latest.isPresent() && latest.get().desired().compareTo(next.desired()) == 0
                && latest.get().odFloor().compareTo(next.odFloor()) == 0
                && latest.get().originalSpot().compareTo(next.originalSpot()) == 0) {
            return null;
        }
        return next;
    }

    /**
     * {@code P(D) = floor(max(0, D - origBase) x (100 - origOnDemandPctAboveBase) / 100)} from the snapshot's
     * original {@code InstancesDistribution} (design-ledger.md §3.2). A group whose original template was
     * not a mixed policy ran On-Demand only, so its original Spot share is 0. An unreadable snapshot gives
     * 0 as well, which can only raise the counterfactual share the calculator removes (P is subtracted), so
     * the unreadable case is not a way to overstate savings: a missing snapshot leaves the entry UNKNOWN
     * (MISSING_SNAPSHOT) in the calculator.
     */
    static BigDecimal originalSpotUnits(String snapshotPayload, BigDecimal desired) {
        if (snapshotPayload == null) {
            return BigDecimal.ZERO;
        }
        try {
            JsonNode mixed = new ObjectMapper().readTree(snapshotPayload).path("owned").path("mixedInstancesPolicy");
            if (mixed.isMissingNode() || mixed.isNull()) {
                return BigDecimal.ZERO;
            }
            JsonNode dist = mixed.path("instancesDistribution");
            BigDecimal base = dist.hasNonNull("onDemandBaseCapacity")
                    ? BigDecimal.valueOf(dist.get("onDemandBaseCapacity").asLong()) : BigDecimal.ZERO;
            BigDecimal pct = dist.hasNonNull("onDemandPercentageAboveBaseCapacity")
                    ? BigDecimal.valueOf(dist.get("onDemandPercentageAboveBaseCapacity").asLong())
                    : BigDecimal.valueOf(100L);
            BigDecimal above = desired.subtract(base).max(BigDecimal.ZERO);
            return above.multiply(BigDecimal.valueOf(100L).subtract(pct)).divide(BigDecimal.valueOf(100L), 0,
                    RoundingMode.FLOOR);
        } catch (IOException | RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    // ------------------------------------------------------------------ AWS reads

    /** Instance type to weight; 1 when the group does not weight its overrides, 0 (unknown) for a type missing from a weighted list. */
    static Map<String, BigDecimal> weights(AutoScalingGroup asg) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (asg.mixedInstancesPolicy() == null || asg.mixedInstancesPolicy().launchTemplate() == null) {
            return out;
        }
        for (LaunchTemplateOverrides o : asg.mixedInstancesPolicy().launchTemplate().overrides()) {
            if (o.instanceType() != null && o.weightedCapacity() != null && !o.weightedCapacity().isBlank()) {
                out.put(o.instanceType(), new BigDecimal(o.weightedCapacity().trim()));
            }
        }
        return out;
    }

    private static IntervalTracker.Present toPresent(Instance i, Map<String, BigDecimal> weights) {
        String type = i.instanceTypeAsString();
        BigDecimal weight = weights.isEmpty() ? BigDecimal.ONE : weights.getOrDefault(type, BigDecimal.ZERO);
        Platform platform = AwsEstimatePriceService.platformOf(i.platformDetails());
        boolean spot = "spot".equals(i.instanceLifecycleAsString());
        return new IntervalTracker.Present(i.instanceId(), type,
                i.placement() == null ? null : i.placement().availabilityZone(),
                platform == Platform.UNKNOWN ? null : platform.name(), spot ? "SPOT" : "ON_DEMAND",
                weight.setScale(6, RoundingMode.UNNECESSARY), i.launchTime());
    }

    private Map<String, Activity> terminations(AwsReadOnlyView view, String name, Instant now) {
        List<Activity> activities = view.autoScaling()
                .describeScalingActivities(r -> r.autoScalingGroupName(name).maxRecords(MAX_ACTIVITIES)).activities();
        Map<String, Activity> out = new LinkedHashMap<>();
        for (Activity a : activities) {
            if (a.description() == null || a.startTime() == null) {
                continue;
            }
            String status = a.statusCodeAsString();
            if ("Failed".equals(status) || "Cancelled".equals(status)) {
                continue;
            }
            Matcher m = TERMINATING.matcher(a.description());
            if (m.find() && a.startTime().isAfter(now.minus(RECENT_ACTIVITY))) {
                out.putIfAbsent(m.group(1), a);
            }
        }
        return out;
    }

    private Map<String, Instance> describe(AwsReadOnlyView view, Set<String> ids) {
        Map<String, Instance> out = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += DESCRIBE_BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + DESCRIBE_BATCH));
            // A filter, not instanceIds: ended instances vanish after about an hour, and an unknown id in
            // instanceIds would fail the whole call.
            for (Reservation r : view.ec2().describeInstances(DescribeInstancesRequest.builder()
                    .filters(Filter.builder().name("instance-id").values(batch).build()).build()).reservations()) {
                for (Instance i : r.instances()) {
                    out.put(i.instanceId(), i);
                }
            }
        }
        return out;
    }
}
