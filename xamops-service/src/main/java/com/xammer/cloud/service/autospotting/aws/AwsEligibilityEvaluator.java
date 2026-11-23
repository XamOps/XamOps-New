package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.AccountFacts;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.CommitmentInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.HookInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.Inputs;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.ProfileChoice;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.QuotaInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.TypeInfo;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The single evaluator behind list, plan and confirm (design-aws.md §7, FR-ELG-01), run in LIST,
 * PLAN or CONFIRM mode (design-core.md §9.2). It reads through an {@link AwsReadOnlyView} and hands
 * the values to the pure {@link AwsEligibilityRules}.
 *
 * <ul>
 *   <li><strong>LIST</strong> runs only what the list's own describe answers, plus the launch
 *       template and AMI read (cached 24 h, one per distinct version) that pricing needs anyway.
 *       Every other check is answered from the 24 h Redis cache of successes (keyed by account, group
 *       key, check code and live fingerprint) or reported {@code DEFERRED}.</li>
 *   <li><strong>PLAN / CONFIRM</strong> run every check fresh and cache each success.</li>
 *   <li>A failed read is a BLOCKED {@code CHECK_FAILED} naming the check and its {@code ErrorKind},
 *       never a pass (AC-ELG-03). Only passes are cached, never a blocker or a failure.</li>
 *   <li>Every LIST evaluation counts its blockers on {@code autospotting.eligibility.blocked}
 *       (NO-07), so {@code EC2_HEALTH_WITH_LB} exclusions on real accounts can be counted.</li>
 * </ul>
 * Read only: nothing here can call a mutating API.
 */
@Component
public class AwsEligibilityEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AwsViewSource views;
    private final AwsGroupStatusReader statusReader;
    private final AwsPlanReader reader;
    private final EligibilityCache cache;
    private final AwsCommitmentUtilization commitments;
    private final AwsRegionCoverage coverage;
    private final GroupLookup groupLookup;
    private final AutoSpottingMetrics metrics;
    private final AwsTypeSelector selector;

    @Autowired
    public AwsEligibilityEvaluator(AwsViewSource views, AwsGroupStatusReader statusReader, AwsPlanReader reader,
                                   EligibilityCache cache, AwsCommitmentUtilization commitments,
                                   AwsRegionCoverage coverage, GroupLookup groupLookup, AutoSpottingMetrics metrics,
                                   @Value("${autospotting.aws.types.target-count:12}") int targetCount) {
        this.views = views;
        this.statusReader = statusReader;
        this.reader = reader;
        this.cache = cache;
        this.commitments = commitments;
        this.coverage = coverage;
        this.groupLookup = groupLookup;
        this.metrics = metrics;
        this.selector = new AwsTypeSelector(targetCount);
    }

    /** The type selector configured with {@code autospotting.aws.types.target-count}. */
    public AwsTypeSelector selector() {
        return selector;
    }

    /** What an evaluation read, so plan creation can reuse it without a second pass. */
    public record Evaluation(EligibilityResult result, AutoScalingGroup rawGroup, AwsGroupFacts group,
                             AwsLaunchInfo launch, AwsPlanReader.TypePlan typePlan, QuotaInfo spotQuota,
                             QuotaInfo odQuota, String fingerprint) {
    }

    /** Evaluate a group by key ({@code region/name}); the group is described here. */
    public Evaluation evaluate(CloudAccount account, String groupKey, Profile profile, EvalMode mode) {
        return evaluate(account, groupKey, profile, mode, false);
    }

    /** As above, with the customer's per-plan burstable choice (AD-09). */
    public Evaluation evaluate(CloudAccount account, String groupKey, Profile profile, EvalMode mode,
                               boolean allowBurstable) {
        return evaluate(account, groupKey, profile, mode, allowBurstable, List.of());
    }

    /**
     * As above, plus the burstable types XamOps added earlier ({@code desired_config.options.burstableAdded}). With the
     * option off, a plan that only removes them from an already-converted group is never blocked for lack of
     * alternatives: the customer is turning the option off, so the plan has nothing to add (AD-09).
     */
    public Evaluation evaluate(CloudAccount account, String groupKey, Profile profile, EvalMode mode,
                               boolean allowBurstable, List<String> removingBurstable) {
        String[] parts = split(groupKey);
        return evaluate(account, parts[0], parts[1], null, profile, mode, null, allowBurstable, removingBurstable);
    }

    /**
     * Evaluate with a group the caller already described (the list's own describe) and, optionally,
     * the caller's view (so a list reuses its regional view).
     */
    public Evaluation evaluate(CloudAccount account, String region, String name, AutoScalingGroup described,
                               Profile profile, EvalMode mode, AwsReadOnlyView suppliedView) {
        return evaluate(account, region, name, described, profile, mode, suppliedView, false);
    }

    public Evaluation evaluate(CloudAccount account, String region, String name, AutoScalingGroup described,
                               Profile profile, EvalMode mode, AwsReadOnlyView suppliedView, boolean allowBurstable) {
        return evaluate(account, region, name, described, profile, mode, suppliedView, allowBurstable, List.of());
    }

    /** The full form: {@code allowBurstable} is the customer's per-plan option (AD-09), false everywhere else. */
    public Evaluation evaluate(CloudAccount account, String region, String name, AutoScalingGroup described,
                               Profile profile, EvalMode mode, AwsReadOnlyView suppliedView, boolean allowBurstable,
                               List<String> removingBurstable) {
        AwsReadOnlyView view = suppliedView != null && region.equals(suppliedView.region())
                ? suppliedView : views.view(account, region);
        String groupKey = region + "/" + name;

        // 1. The group itself.
        Sig<Optional<AwsGroupFacts>> groupSig;
        AutoScalingGroup raw = described;
        AwsGroupFacts facts = null;
        try {
            if (raw == null) {
                raw = statusReader.describeGroup(view, name).orElse(null);
            }
            facts = raw == null ? null : AwsGroupFacts.from(region, raw);
            groupSig = Sig.ok(Optional.ofNullable(facts));
        } catch (AwsReadException e) {
            groupSig = Sig.failed(e.kind());
        }
        String fingerprint = facts == null ? "none" : AwsFingerprint.of(facts);

        AccountFacts accountFacts = new AccountFacts(
                !(account.getAccessType() != null && "read-write".equalsIgnoreCase(account.getAccessType().trim())),
                account.getStackTemplateVersion(), coverage.covered(account, region));
        ProfileChoice profileChoice = profileChoice(profile);

        Sig<AwsLaunchInfo> launchSig = Sig.deferred();
        AwsLaunchInfo launch = null;
        Sig<Boolean> ecs = Sig.deferred();
        Sig<Boolean> refresh = Sig.deferred();
        Sig<Boolean> other = Sig.deferred();
        Sig<List<String>> perms = Sig.deferred();
        Sig<QuotaInfo> spot = Sig.deferred();
        Sig<QuotaInfo> od = Sig.deferred();
        Sig<List<HookInfo>> hooks = Sig.deferred();
        Sig<Integer> dereg = Sig.deferred();
        Sig<CommitmentInfo> commit = Sig.deferred();
        Sig<TypeInfo> types = Sig.deferred();
        Sig<Boolean> reservations = Sig.deferred();
        AwsPlanReader.TypePlan typePlan = null;

        if (facts != null) {
            final AwsGroupFacts g = facts;
            final AutoScalingGroup rawGroup = raw;
            boolean hasTemplate = g.launchConfigurationName() == null && g.launchTemplate() != null;
            if (hasTemplate) {
                launchSig = attempt(() -> reader.launch(account, view, g.launchTemplate()));
                if (launchSig instanceof Sig.Ok<AwsLaunchInfo> ok) {
                    launch = ok.value();
                }
            }
            // Cheap, not an AWS call: the lease holder of the spine row.
            other = Sig.ok(groupLookup.ebsHoldsLease(Provider.AWS, account.getId(), groupKey));

            ecs = gated(account, groupKey, fingerprint, mode, "ECS_MANAGED_TERMINATION", false, v -> !v,
                    () -> reader.ecsManagedTermination(view, g.arn()));
            refresh = gated(account, groupKey, fingerprint, mode, "REFRESH_IN_PROGRESS", false, v -> !v,
                    () -> reader.refreshInProgress(view, g.name()));
            hooks = gated(account, groupKey, fingerprint, mode, "LIFECYCLE_HOOKS", List.<HookInfo>of(), List::isEmpty,
                    () -> reader.lifecycleHooks(view, g.name()));
            dereg = gated(account, groupKey, fingerprint, mode, "DEREG_DELAY_LONG", 0,
                    d -> d <= AwsEligibilityRules.LONG_DEREGISTRATION_DELAY,
                    () -> reader.maxDeregistrationDelay(view, g.targetGroupArns()));
            commit = gated(account, groupKey, fingerprint, mode, "COMMITMENT_UTILIZATION",
                    new CommitmentInfo(null, commitments.thresholdPercent()),
                    c -> c.utilizationPercent() == null || c.utilizationPercent() >= c.thresholdPercent(),
                    () -> new CommitmentInfo(commitments.utilization(account).orElse(null),
                            commitments.thresholdPercent()));
            final AwsLaunchInfo li = launch;
            if (hasTemplate && li != null) {
                reservations = gated(account, groupKey, fingerprint, mode, "OPEN_CAPACITY_RESERVATION", false,
                        v -> !v, () -> reader.openReservations(view, li.instanceType()));
                perms = gated(account, groupKey, fingerprint, mode, "PERMISSION_MISSING", List.<String>of(),
                        List::isEmpty, () -> reader.missingPermissions(view, account.getRoleArn(), li));
                // A pass with the option on says nothing about the option off, so the cache code differs.
                final String typesCode = allowBurstable ? "NO_COMPATIBLE_TYPES+BURSTABLE" : "NO_COMPATIBLE_TYPES";
                final AwsTypeSelector chosen = selector.withBurstable(allowBurstable);
                Sig<AwsPlanReader.TypePlan> typeRead = gatedRaw(account, groupKey, fingerprint, mode,
                        typesCode, () -> reader.types(account, view, g, li, chosen),
                        tp -> !tp.selection().noCompatibleTypes() && !tp.selection().fewTypes() && !tp.xenToNitro());
                if (typeRead instanceof Sig.Ok<AwsPlanReader.TypePlan> ok) {
                    typePlan = ok.value();
                    AwsTypeSelector.Selection selection = typePlan.selection();
                    if (!allowBurstable && onlyRemoves(g, removingBurstable) && selection.noCompatibleTypes()) {
                        // Nothing is added, so "no other type to add" is not a reason to stop the removal.
                        selection = new AwsTypeSelector.Selection(selection.types(), AwsTypeSelector.FEW_TYPES_THRESHOLD);
                    }
                    types = Sig.ok(new TypeInfo(selection, typePlan.xenToNitro(),
                            typePlan.burstableAlternatives(), typePlan.burstableSelected()));
                } else if (typeRead instanceof Sig.Failed<AwsPlanReader.TypePlan> f) {
                    types = Sig.failed(f.kind());
                } else if (cache.isPassCached(account.getId(), groupKey, typesCode, fingerprint)) {
                    types = Sig.ok(new TypeInfo(new AwsTypeSelector.Selection(List.of(), 99), false));
                }
                // Quotas: need the Spot count the profile expects and the original type's vCPU.
                int spotTarget = spotTarget(g.desired(), profileChoice);
                if (spotTarget <= 0) {
                    spot = Sig.ok(new QuotaInfo(AwsPlanReader.SPOT_QUOTA_CODE, 0, 0, 0, true, "none"));
                    od = Sig.ok(new QuotaInfo(AwsPlanReader.OD_QUOTA_CODE, 0, 0, 0, false, "none"));
                } else if (typePlan != null) {
                    final int vcpu = typePlan.originalVcpu();
                    spot = gated(account, groupKey, fingerprint, mode, "SPOT_VCPU_QUOTA",
                            new QuotaInfo(AwsPlanReader.SPOT_QUOTA_CODE, 0, 0, 0, true, "cache"),
                            q -> q.needed() <= q.remaining()
                                    && (q.limit() <= 0 || q.remaining() - q.needed() >= AwsEligibilityRules.QUOTA_MARGIN * q.limit()),
                            () -> reader.spotQuota(view, spotTarget, vcpu));
                    od = gated(account, groupKey, fingerprint, mode, "OD_VCPU_QUOTA",
                            new QuotaInfo(AwsPlanReader.OD_QUOTA_CODE, 0, 0, 0, false, "cache"),
                            q -> q.needed() <= q.remaining(),
                            () -> reader.onDemandQuota(view, spotTarget, vcpu));
                } else {
                    spot = cachedOnly(account, groupKey, fingerprint, "SPOT_VCPU_QUOTA",
                            new QuotaInfo(AwsPlanReader.SPOT_QUOTA_CODE, 0, 0, 0, true, "cache"));
                    od = cachedOnly(account, groupKey, fingerprint, "OD_VCPU_QUOTA",
                            new QuotaInfo(AwsPlanReader.OD_QUOTA_CODE, 0, 0, 0, false, "cache"));
                }
            }
            Inputs inputs = new Inputs(groupSig, accountFacts, profileChoice, launchSig, ecs, refresh, other, perms,
                    spot, od, hooks, dereg, commit, types, reservations);
            return finish(account, mode, AwsEligibilityRules.evaluate(inputs), rawGroup, g, launch, typePlan, spot,
                    od, fingerprint);
        }
        Inputs inputs = new Inputs(groupSig, accountFacts, profileChoice, launchSig, ecs, refresh, other, perms,
                spot, od, hooks, dereg, commit, types, reservations);
        return finish(account, mode, AwsEligibilityRules.evaluate(inputs), raw, null, null, null, spot, od,
                fingerprint);
    }

    /** True when the group is already converted and every type in {@code removing} is one of its live overrides. */
    private static boolean onlyRemoves(AwsGroupFacts g, List<String> removing) {
        return removing != null && !removing.isEmpty() && g.mixedInstancesPolicy() && !g.attributeBasedOverrides()
                && g.overrideTypes().containsAll(removing) && g.overrideTypes().size() > removing.size();
    }

    private Evaluation finish(CloudAccount account, EvalMode mode, EligibilityResult result, AutoScalingGroup raw,
                              AwsGroupFacts facts, AwsLaunchInfo launch, AwsPlanReader.TypePlan typePlan,
                              Sig<QuotaInfo> spot, Sig<QuotaInfo> od, String fingerprint) {
        if (mode == EvalMode.LIST) {
            result.blockers().forEach(b -> metrics.eligibilityBlocked(Provider.AWS, b.code()));
        }
        return new Evaluation(result, raw, facts, launch, typePlan,
                spot instanceof Sig.Ok<QuotaInfo> s ? s.value() : null,
                od instanceof Sig.Ok<QuotaInfo> o ? o.value() : null, fingerprint);
    }

    // ---- gating and caching ----

    /** A cached-success read: LIST uses the cache or defers; PLAN/CONFIRM read fresh and cache a pass. */
    private <T> Sig<T> gated(CloudAccount account, String groupKey, String fingerprint, EvalMode mode, String code,
                             T passValue, Predicate<T> isPass, Supplier<T> read) {
        if (mode == EvalMode.LIST) {
            return cache.isPassCached(account.getId(), groupKey, code, fingerprint) ? Sig.ok(passValue) : Sig.deferred();
        }
        Sig<T> sig = attempt(read);
        if (sig instanceof Sig.Ok<T> ok && isPass.test(ok.value())) {
            cache.recordPass(account.getId(), groupKey, code, fingerprint);
        }
        return sig;
    }

    /** Like {@link #gated} for a value with no meaningful pass stand-in; LIST returns deferred on a miss. */
    private <T> Sig<T> gatedRaw(CloudAccount account, String groupKey, String fingerprint, EvalMode mode, String code,
                                Supplier<T> read, Predicate<T> isPass) {
        if (mode == EvalMode.LIST) {
            return Sig.deferred();
        }
        Sig<T> sig = attempt(read);
        if (sig instanceof Sig.Ok<T> ok && isPass.test(ok.value())) {
            cache.recordPass(account.getId(), groupKey, code, fingerprint);
        }
        return sig;
    }

    private <T> Sig<T> cachedOnly(CloudAccount account, String groupKey, String fingerprint, String code, T pass) {
        return cache.isPassCached(account.getId(), groupKey, code, fingerprint) ? Sig.ok(pass) : Sig.deferred();
    }

    private static <T> Sig<T> attempt(Supplier<T> read) {
        try {
            return Sig.ok(read.get());
        } catch (RuntimeException e) {
            return Sig.failed(AwsErrors.classify(e));
        }
    }

    // ---- profile ----

    private static int spotTarget(int desired, ProfileChoice choice) {
        if (desired <= 1) {
            return 0;
        }
        if (choice != null && choice.params() != null) {
            return AwsProfiles.spotCount(desired, choice.params().base(), choice.params().percentAboveBase());
        }
        if (choice != null) {
            return 0; // unknown profile: already a CHECK_FAILED
        }
        return AwsProfiles.preselectedAt(desired)
                .map(n -> AwsProfiles.byName(n))
                .map(p -> AwsProfiles.spotCount(desired, p.base(), p.percentAboveBase()))
                .orElseGet(() -> AwsProfiles.evaluateNamed(desired).stream().filter(p -> !p.blocked())
                        .mapToInt(AwsProfiles.EvaluatedProfile::spotCount).findFirst().orElse(0));
    }

    /** Parse the SPI profile; an unknown id yields a choice with null params (fails closed). */
    static ProfileChoice profileChoice(Profile profile) {
        if (profile == null) {
            return null;
        }
        String norm = profile.id().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            AwsProfiles.ProfileName name = AwsProfiles.ProfileName.valueOf(norm);
            if (name == AwsProfiles.ProfileName.CUSTOM) {
                if (profile.parametersJson() == null) {
                    return new ProfileChoice(profile.id(), null);
                }
                JsonNode n = MAPPER.readTree(profile.parametersJson());
                return new ProfileChoice(profile.id(), AwsProfiles.custom(n.path("base").asInt(1),
                        n.path("percentAboveBase").asInt(0)));
            }
            return new ProfileChoice(profile.id(), AwsProfiles.byName(name));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return new ProfileChoice(profile.id(), null);
        }
    }

    static String[] split(String groupKey) {
        int slash = groupKey.indexOf('/');
        if (slash <= 0 || slash == groupKey.length() - 1) {
            throw new IllegalArgumentException("groupKey must be region/name: " + groupKey);
        }
        return new String[] {groupKey.substring(0, slash), groupKey.substring(slash + 1)};
    }

    /** Whether a failed read of kind {@code kind} should read as a missing group rather than an error. */
    static boolean isNotFound(ErrorKind kind) {
        return kind == ErrorKind.NOT_FOUND;
    }
}
