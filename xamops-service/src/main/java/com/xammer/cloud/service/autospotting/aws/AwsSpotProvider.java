package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.QuotaInfo;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PriceKey;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PricedRate;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The AWS {@link SpotProvider} (design-aws.md §2.1). Thin: it delegates to the evaluator, status
 * reader, snapshot codec, type selector and estimate service. <strong>Read only in this phase.</strong>
 *
 * <ul>
 *   <li>{@code checkEligibility}, {@code createPlan}, {@code takeSnapshot}, {@code getStatus} read
 *       through an {@link AwsReadOnlyView} built from the stored read role at runtime; no mutating
 *       client is reachable from them (AC-PLN-01).</li>
 *   <li>{@code reconcile} is a pure decision. Health decisions return no fallback action until
 *       Phase 20.</li>
 *   <li>{@code executeConversion} returns the {@link AwsStepLibrary} program and {@code restore} the
 *       REVERT program ({@link AwsRestoreProgram}), but only while {@link AwsProviderGate} is open: both
 *       {@code autospotting.aws.v2-mutations-enabled} and {@code autospotting.aws.conversions-enabled}
 *       stay false until Phase 23, so each refuses with {@code AS_PROVIDER_NOT_READY} (the second lock
 *       behind switch (p) on the confirm path).</li>
 * </ul>
 */
@Component
public class AwsSpotProvider implements SpotProvider {

    static final int SNAPSHOT_SCHEMA = AwsSnapshotCodec.SCHEMA_VERSION;
    static final double HOURS_PER_MONTH = 730.0;
    static final String SPOT_STRATEGY = "price-capacity-optimized";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final CloudAccountRepository accounts;
    private final AwsViewSource views;
    private final AwsEligibilityEvaluator evaluator;
    private final AwsGroupStatusReader statusReader;
    private final AwsEstimatePriceService prices;
    private final AutoSpottingSwitchProperties switchProperties;
    private final AwsProviderGate gate;
    private final AwsStepLibrary library;
    private final AwsRevertProgramFactory revertPrograms;
    private final AwsRolloutEvaluator rollout;
    private final AwsFallbackRules fallbackRules;
    private final AwsFallbackPrograms fallbackPrograms;
    private final AwsLaunchEventRecorder events;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AwsSpotProvider(CloudAccountRepository accounts, AwsViewSource views, AwsEligibilityEvaluator evaluator,
                           AwsGroupStatusReader statusReader, AwsEstimatePriceService prices,
                           AutoSpottingSwitchProperties switchProperties, AwsProviderGate gate,
                           AwsStepLibrary library, AwsRevertProgramFactory revertPrograms,
                           AwsRolloutEvaluator rollout, AwsFallbackRules fallbackRules,
                           AwsFallbackPrograms fallbackPrograms, AwsLaunchEventRecorder events) {
        this(accounts, views, evaluator, statusReader, prices, switchProperties, gate, library, revertPrograms,
                rollout, fallbackRules, fallbackPrograms, events, Clock.systemUTC());
    }

    AwsSpotProvider(CloudAccountRepository accounts, AwsViewSource views, AwsEligibilityEvaluator evaluator,
                    AwsGroupStatusReader statusReader, AwsEstimatePriceService prices,
                    AutoSpottingSwitchProperties switchProperties, AwsProviderGate gate, AwsStepLibrary library,
                    AwsRevertProgramFactory revertPrograms, AwsRolloutEvaluator rollout,
                    AwsFallbackRules fallbackRules, AwsFallbackPrograms fallbackPrograms,
                    AwsLaunchEventRecorder events, Clock clock) {
        this.clock = clock;
        this.fallbackRules = fallbackRules;
        this.fallbackPrograms = fallbackPrograms;
        this.events = events;
        this.accounts = accounts;
        this.views = views;
        this.evaluator = evaluator;
        this.statusReader = statusReader;
        this.prices = prices;
        this.switchProperties = switchProperties;
        this.gate = gate;
        this.library = library;
        this.revertPrograms = revertPrograms;
        this.rollout = rollout;
    }

    @Override
    public Provider provider() {
        return Provider.AWS;
    }

    // ---- eligibility ----

    @Override
    public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode) {
        return checkEligibility(ref, view, profile, mode, PlanOptions.none());
    }

    @Override
    public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode,
                                              PlanOptions options) {
        Optional<CloudAccount> account = account(ref.cloudAccountId());
        if (account.isEmpty()) {
            return new EligibilityResult(List.of(EligibilityResult.Check.failed("b-CHECK_FAILED-ACCOUNT",
                    "The connected account could not be found.", ErrorKind.NOT_FOUND)), List.of(), List.of());
        }
        boolean allowBurstable = options != null && options.effectiveAllowBurstable();
        AwsEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account.get(), ref.groupKey(), profile, mode,
                allowBurstable, allowBurstable || options == null ? List.of() : options.storedBurstableAdded());
        EligibilityResult result = withOriginalKept(ev.result(), options, ev.group());
        return AwsMaintenancePolicy.withoutOwnedBlocker(result, ownsPolicy(options, ev.group()));
    }

    // ---- plan ----

    @Override
    public PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view) {
        CloudAccount account = account(req.ref().cloudAccountId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        if (req.kind() == OperationKind.REVERT) {
            // A revert is never blocked by the conversion-only checks (DR-03): its own plan, read only.
            return revertPrograms.plan(req, account);
        }
        Profile profile = req.profile() == null ? new Profile("conservative", null) : req.profile();
        PlanOptions options = req.options();
        AwsEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account, req.ref().groupKey(), profile, EvalMode.PLAN,
                options.effectiveAllowBurstable(),
                options.effectiveAllowBurstable() ? List.of() : options.storedBurstableAdded());
        AwsGroupFacts g = ev.group();
        boolean originalBurstable = ev.typePlan() != null && ev.typePlan().originalBurstable();
        if (options.explicitlyAllowsBurstable() && ev.typePlan() != null && !originalBurstable) {
            // AD-09: the option only widens a burstable original's list; it never adds burstable to anything else.
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BURSTABLE_NOT_APPLICABLE",
                    "This group does not use a burstable instance type, so burstable alternatives do not apply.");
        }
        boolean allowBurstable = options.effectiveAllowBurstable() && originalBurstable;
        boolean ownsPolicy = ownsPolicy(options, g);
        if (g == null || ev.rawGroup() == null) {
            // Not found or unreadable: the eligibility already says why; the body carries no cloud facts.
            return new PlanBody(null, null, null, null, ev.result(), null, List.of(), null, null, null);
        }
        AwsEligibilityRules.ProfileChoice choice = AwsEligibilityEvaluator.profileChoice(profile);
        AwsProfiles.ProfileParams params = choice != null && choice.params() != null ? choice.params()
                : AwsProfiles.byName(AwsProfiles.preselectedDefault());
        int spotTarget = g.desired() <= 1 ? 0 : AwsProfiles.spotCount(g.desired(), params.base(), params.percentAboveBase());

        String current = write(currentNode(g, ownsPolicy));
        ObjectNode target = targetNode(g, ev, params, allowBurstable, ownsPolicy,
                options.storedBurstableAdded());
        String diff = write(diffNode(g, target));

        SnapshotPayload snapshot = snapshotFor(g, ev);
        PlanBody.Rollout rollout = rollout(g, ev, spotTarget);
        List<PlanBody.Estimate> estimates = estimates(account, g, ev);
        String floor = write(JSON.createObjectNode()
                .put("profile", params.name().name())
                .put("baseCapacity", params.base())
                .put("instanceType", ev.launch() == null ? null : ev.launch().instanceType())
                .put("onDemandCount", Math.max(0, g.desired() - spotTarget))
                .put("fullSpot", params.base() == 0 && params.percentAboveBase() == 0)
                .put("allocationStrategy", "prioritized"));
        String revert = write(JSON.createObjectNode()
                .put("program", "REVERT")
                .put("method", "RESTORE_SNAPSHOT")
                .put("exact", snapshot.exact()));
        if (options.keepsOriginal() && req.kind() == com.xammer.cloud.domain.autospotting.OperationKind.ENABLE) {
            // D-23b-03: XamOps' configuration never left the group (drain-only revert), so the linked snapshot is
            // still the customer's original; the enable keeps it instead of saving the drained group as "original".
            // Only ENABLE takes a snapshot; the key is a step instruction and is not kept in desired_config.
            target.put(KEEP_LINKED_SNAPSHOT, true);
        }
        EligibilityResult eligibility = withOriginalKept(ev.result(), options, g);
        return new PlanBody(current, write(target), diff, snapshot, AwsMaintenancePolicy.withoutOwnedBlocker(eligibility, ownsPolicy), rollout, estimates, floor, null, revert,
                g.arn()).withOptions(optionsJson(allowBurstable, ev));
    }

    /** Target key: the ENABLE keeps the group's linked snapshot as the original (D-23b-03). */
    public static final String KEEP_LINKED_SNAPSHOT = "keepLinkedSnapshot";

    /**
     * Whether XamOps owns the live maintenance policy for this plan. The stored claim counts only under management
     * (D-23b-01). After a drain-only revert (D-23b-03) it also needs the live policy to still be XamOps' 100/200: a
     * policy the customer set since is theirs and is never overwritten.
     */
    private static boolean ownsPolicy(PlanOptions options, AwsGroupFacts g) {
        if (options == null || !AwsMaintenancePolicy.owns(options.ownershipConfigJson())) {
            return false;
        }
        if (!options.configOnGroup() || g == null) {
            return true;
        }
        return java.util.Objects.equals(g.minHealthyPercentage(), AwsMaintenancePolicy.OWNED_MIN)
                && java.util.Objects.equals(g.maxHealthyPercentage(), AwsMaintenancePolicy.OWNED_MAX);
    }

    /**
     * D-23b-03: an enable after a drain-only revert keeps the original saved before the first enable, so a later
     * revert restores that original. The plan says so (acknowledged warning) and lists the XamOps-owned fields the
     * customer changed on the group since the drain-only revert: the enable applies on top of them and the later
     * revert restores the original for them. A changed maintenance policy is the customer's and is kept.
     */
    static EligibilityResult withOriginalKept(EligibilityResult r, PlanOptions options, AwsGroupFacts g) {
        if (r == null || options == null || !options.keepsOriginal()) {
            return r;
        }
        List<String> changed = new ArrayList<>();
        if (g != null) {
            try {
                for (String f : AwsOwnedFields.mismatches(g, JSON.readTree(options.desiredConfigJson()))) {
                    // The drain set the share above base to 100: only a different live value is a customer change.
                    boolean drainedShare = "onDemandPercentageAboveBaseCapacity".equals(f)
                            && Integer.valueOf(100).equals(g.onDemandPercentageAboveBase());
                    if (!drainedShare) {
                        changed.add(f);
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // unreadable stored config: no field list, the warning still shows
            }
        }
        StringBuilder text = new StringBuilder("The last revert only drained Spot, so XamOps still has the original "
                + "configuration it saved before the first enable. Enabling keeps it: a later revert restores that "
                + "original.");
        List<String> others = changed.stream().filter(f -> !AwsMaintenancePolicy.KEY.equals(f)).toList();
        if (!others.isEmpty()) {
            text.append(" Changed on the group since that revert: ").append(String.join(", ", others))
                    .append(". XamOps applies this plan on top of them, and a later revert restores the original for them.");
        }
        if (changed.contains(AwsMaintenancePolicy.KEY)) {
            text.append(" Your maintenance policy changed since that revert; it is yours now and XamOps keeps it.");
        }
        List<EligibilityResult.Check> warnings = new ArrayList<>(r.warnings());
        warnings.add(EligibilityResult.Check.of("w-original-snapshot-kept", "ORIGINAL_SNAPSHOT_KEPT", text.toString()));
        return new EligibilityResult(r.blockers(), warnings, r.deferred());
    }

    /** The customer choices and what they can still choose (AD-09): the dialog's burstable control reads it. */
    private static String optionsJson(boolean allowBurstable, AwsEligibilityEvaluator.Evaluation ev) {
        ObjectNode o = JSON.createObjectNode();
        o.put("allowBurstable", allowBurstable);
        boolean applicable = ev.typePlan() != null && ev.typePlan().originalBurstable();
        o.put("burstableApplicable", applicable);
        o.set("burstableAlternatives", strings(ev.typePlan() == null ? List.of() : ev.typePlan().burstableAlternatives()));
        o.set("burstableAdded", strings(ev.typePlan() == null ? List.of() : ev.typePlan().burstableSelected()));
        return write(o);
    }

    private static ObjectNode currentNode(AwsGroupFacts g, boolean ownsPolicy) {
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", g.mixedInstancesPolicy() ? "MIXED_INSTANCES_POLICY" : "LAUNCH_TEMPLATE");
        n.set("launchTemplate", spec(g.launchTemplate()));
        n.set("overrides", strings(g.overrideTypes()));
        if (g.mixedInstancesPolicy()) {
            n.set("instancesDistribution", distribution(g.onDemandAllocationStrategy(), g.onDemandBase(),
                    g.onDemandPercentageAboveBase(), g.spotAllocationStrategy(), g.spotInstancePools()));
        } else {
            n.putNull("instancesDistribution");
        }
        n.put("capacityRebalance", g.capacityRebalance());
        n.put("min", g.min()).put("max", g.max()).put("desired", g.desired());
        if (ownsPolicy) {
            // The saved prior config of a group whose policy XamOps owns carries it, so a compensation keeps 100/200.
            n.set(AwsMaintenancePolicy.KEY, AwsMaintenancePolicy.ownedNode());
        }
        return n;
    }

    /**
     * The target (design-aws.md §2.4, §3.1, §4.4). Owned fields only: never DesiredCapacity, MinSize,
     * MaxSize, health-check fields, AZs or tags, and never {@code SpotMaxPrice}. A group that already
     * has overrides keeps them; types are added only when the list has fewer than 4 and the group does
     * not use attribute-based selection.
     */
    private ObjectNode targetNode(AwsGroupFacts g, AwsEligibilityEvaluator.Evaluation ev, AwsProfiles.ProfileParams p,
                                  boolean allowBurstable, boolean ownsPolicy, List<String> storedBurstableAdded) {
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", "MIXED_INSTANCES_POLICY");
        n.set("launchTemplate", spec(g.launchTemplate()));
        List<String> overrides = new ArrayList<>(g.overrideTypes());
        List<String> burstableAdded = new ArrayList<>();
        if (g.mixedInstancesPolicy() && !allowBurstable) {
            // AD-09: turning the option off removes what XamOps added for it, and only that (a customer's own stay).
            overrides.removeAll(storedBurstableAdded);
        } else if (g.mixedInstancesPolicy()) {
            storedBurstableAdded.stream().filter(overrides::contains).forEach(burstableAdded::add);
        }
        List<String> selected = ev.typePlan() == null ? List.of() : ev.typePlan().selection().types();
        if (!g.mixedInstancesPolicy()) {
            overrides = new ArrayList<>(selected);
            if (allowBurstable) {
                selected.stream().filter(ev.typePlan().burstableSelected()::contains).forEach(burstableAdded::add);
            }
        } else if (!g.attributeBasedOverrides() && overrides.size() < AwsTypeSelector.FEW_TYPES_THRESHOLD) {
            for (String t : selected) {
                if (!overrides.contains(t) && overrides.size() < AwsTypeSelector.MAX_TYPES) {
                    overrides.add(t);
                    if (allowBurstable && ev.typePlan().burstableSelected().contains(t)) {
                        burstableAdded.add(t);
                    }
                }
            }
        }
        n.set("overrides", strings(overrides));
        String strategy = preservedStrategy(g);
        ObjectNode d = distribution("prioritized", p.base(), p.percentAboveBase(), strategy,
                "lowest-price".equals(strategy) ? g.spotInstancePools() : null);
        n.set("instancesDistribution", d);
        n.put("capacityRebalance", true);
        // AD-08: a group with no maintenance policy gets 100/200, owned by XamOps and restored at revert. A policy
        // XamOps already owns stays owned. A customer's own policy is never in the target.
        if (ownsPolicy || AwsMaintenancePolicy.classify(g) == AwsMaintenancePolicy.Kind.ABSENT) {
            n.set(AwsMaintenancePolicy.KEY, AwsMaintenancePolicy.ownedNode());
        }
        // AD-09: remembered in desired_config.options, so the next plan starts from this choice.
        ObjectNode opts = n.putObject("options");
        opts.put("allowBurstable", allowBurstable);
        if (!burstableAdded.isEmpty()) {
            opts.set("burstableAdded", strings(burstableAdded));
        }
        if (g.mixedInstancesPolicy() && !allowBurstable && !storedBurstableAdded.isEmpty()) {
            // The option is being turned off: step 0 reads this to know the plan only retires types, even when
            // none of them is live any more (so "no other type to add" does not block the removal).
            opts.set("burstableRemoved", strings(storedBurstableAdded));
        }
        return n;
    }

    /** Strategy preservation (FR-AWS-01): keep capacity-optimized-prioritized and lowest-price; change the rest. */
    static String preservedStrategy(AwsGroupFacts g) {
        String s = g.spotAllocationStrategy();
        if ("capacity-optimized-prioritized".equals(s) || "lowest-price".equals(s)) {
            return s;
        }
        return SPOT_STRATEGY;
    }

    private static ObjectNode distribution(String odStrategy, Integer base, Integer pct, String spotStrategy, Integer pools) {
        ObjectNode d = JSON.createObjectNode();
        d.put("onDemandAllocationStrategy", odStrategy);
        d.put("onDemandBaseCapacity", base);
        d.put("onDemandPercentageAboveBaseCapacity", pct);
        d.put("spotAllocationStrategy", spotStrategy);
        if (pools != null && "lowest-price".equals(spotStrategy)) {
            d.put("spotInstancePools", pools);
        }
        return d; // no SpotMaxPrice, ever (FR-CMP-04)
    }

    private static ArrayNode diffNode(AwsGroupFacts g, ObjectNode target) {
        ArrayNode diff = JSON.createArrayNode();
        addDiff(diff, "shape", g.mixedInstancesPolicy() ? "MIXED_INSTANCES_POLICY" : "LAUNCH_TEMPLATE",
                target.path("shape").asText());
        addDiff(diff, "overrides", String.join(",", g.overrideTypes()), join(target.path("overrides")));
        JsonNode d = target.path("instancesDistribution");
        addDiff(diff, "onDemandAllocationStrategy", g.onDemandAllocationStrategy(),
                d.path("onDemandAllocationStrategy").asText(null));
        addDiff(diff, "onDemandBaseCapacity", str(g.onDemandBase()), str(d.path("onDemandBaseCapacity")));
        addDiff(diff, "onDemandPercentageAboveBaseCapacity", str(g.onDemandPercentageAboveBase()),
                str(d.path("onDemandPercentageAboveBaseCapacity")));
        addDiff(diff, "spotAllocationStrategy", g.spotAllocationStrategy(), d.path("spotAllocationStrategy").asText(null));
        addDiff(diff, "capacityRebalance", String.valueOf(g.capacityRebalance()),
                String.valueOf(target.path("capacityRebalance").asBoolean()));
        if (AwsMaintenancePolicy.hasValue(target)) {
            JsonNode tp = target.path(AwsMaintenancePolicy.KEY);
            addDiff(diff, AwsMaintenancePolicy.KEY, AwsRestoreProgram.policyText(JSON.createObjectNode()
                            .put("min", g.minHealthyPercentage()).put("max", g.maxHealthyPercentage())),
                    "min " + tp.get("min").asInt() + " / max " + tp.get("max").asInt());
        } else if (AwsMaintenancePolicy.classify(g) != AwsMaintenancePolicy.Kind.ABSENT) {
            // A customer's policy is never rewritten; the plan says so instead of staying silent (design-ux-ops 3.5).
            addDiff(diff, AwsMaintenancePolicy.KEY, AwsRestoreProgram.policyText(JSON.createObjectNode()
                    .put("min", g.minHealthyPercentage()).put("max", g.maxHealthyPercentage())), "unchanged");
        }
        return diff;
    }

    private static void addDiff(ArrayNode diff, String field, String from, String to) {
        if (java.util.Objects.equals(from, to)) {
            return;
        }
        ObjectNode n = diff.addObject();
        n.put("field", field);
        n.put("from", from);
        n.put("to", to);
    }

    private SnapshotPayload snapshotFor(AwsGroupFacts g, AwsEligibilityEvaluator.Evaluation ev) {
        Integer version = ev.launch() == null ? null : ev.launch().resolvedVersion();
        String sha = ev.launch() == null ? null : ev.launch().versionSha256();
        AwsSnapshotCodec.Context ctx = new AwsSnapshotCodec.Context(version, sha, null, List.of(), List.of(), List.of());
        return AwsSnapshotCodec.encode(g.region(), ev.rawGroup(), ctx, g.hasLegacyTag() && !g.hasMarker());
    }

    private PlanBody.Rollout rollout(AwsGroupFacts g, AwsEligibilityEvaluator.Evaluation ev, int spotTarget) {
        // Instances to move to Spot: the target Spot count less any Spot already running is unknown
        // here (no market type in the describe), so the plan states the target count.
        ObjectNode q = JSON.createObjectNode();
        if (ev.spotQuota() != null) {
            QuotaInfo s = ev.spotQuota();
            ObjectNode sn = q.putObject("spot");
            sn.put("quotaCode", s.quotaCode()).put("limit", s.limit()).put("usage", s.usage()).put("needed", s.needed());
            sn.put("margin", s.remaining() - s.needed());
        }
        if (ev.odQuota() != null) {
            QuotaInfo o = ev.odQuota();
            ObjectNode on = q.putObject("onDemand");
            on.put("quotaCode", o.quotaCode()).put("limit", o.limit()).put("usage", o.usage())
                    .put("neededForFallback", o.needed());
        }
        q.put("source", ev.spotQuota() == null ? "none" : ev.spotQuota().source());
        q.put("asOf", clock.instant().toString());
        int headroom = Math.max(0, g.max() - g.desired());
        return new PlanBody.Rollout("CAPACITY_REBALANCE_LAUNCH_FIRST", spotTarget, 1, "10 min to 2 h",
                headroom + " instance(s) above desired (max " + g.max() + ")", write(q));
    }

    // ---- estimates (§7.5, core §9.5) ----

    private List<PlanBody.Estimate> estimates(CloudAccount account, AwsGroupFacts g, AwsEligibilityEvaluator.Evaluation ev) {
        List<PlanBody.Estimate> out = new ArrayList<>();
        for (AwsProfiles.EvaluatedProfile p : AwsProfiles.evaluateNamed(g.desired())) {
            String name = p.name().name().toLowerCase(java.util.Locale.ROOT);
            if (p.blocked()) {
                out.add(new PlanBody.Estimate(name, 0.0, "[]"));
                continue;
            }
            out.add(estimate(account, g, ev, name, p.spotCount()));
        }
        return out;
    }

    /**
     * Monthly savings = Σ over the expected Spot instances of (On-Demand − Spot) × 730 h. If any input
     * is not priced the figure is null, "Not priced", never a partial sum and never 0 (AC-PLN-09).
     */
    PlanBody.Estimate estimate(CloudAccount account, AwsGroupFacts g, AwsEligibilityEvaluator.Evaluation ev,
                               String profile, int spotCount) {
        AwsLaunchInfo launch = ev.launch();
        if (launch == null || launch.instanceType() == null) {
            return new PlanBody.Estimate(profile, null, "[]");
        }
        EstimatePriceService.Platform platform = AwsEstimatePriceService.platformOf(launch.platformDetails());
        String type = launch.instanceType();
        PricedRate od = prices.onDemand(account, new PriceKey(g.region(), null, type, platform, "Shared"));
        if (!od.priced()) {
            return new PlanBody.Estimate(profile, null, "[]");
        }
        List<BigDecimal> spots = new ArrayList<>();
        Instant spotAsOf = null;
        for (String az : g.availabilityZones()) {
            PricedRate s = prices.spot(account, new PriceKey(g.region(), az, type, platform, "Shared"));
            if (!s.priced()) {
                return new PlanBody.Estimate(profile, null, "[]");
            }
            spots.add(s.ratePerHour());
            if (s.asOf() != null && (spotAsOf == null || s.asOf().isBefore(spotAsOf))) {
                spotAsOf = s.asOf();
            }
        }
        if (spots.isEmpty()) {
            return new PlanBody.Estimate(profile, null, "[]");
        }
        spots.sort(BigDecimal::compareTo);
        int n = spots.size();
        BigDecimal median = n % 2 == 1 ? spots.get(n / 2)
                : spots.get(n / 2 - 1).add(spots.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        BigDecimal perInstance = od.ratePerHour().subtract(median).multiply(BigDecimal.valueOf(HOURS_PER_MONTH));
        double monthly = perInstance.multiply(BigDecimal.valueOf(spotCount)).setScale(2, RoundingMode.HALF_UP).doubleValue();
        ArrayNode sources = JSON.createArrayNode();
        sources.addObject().put("source", od.source()).put("asOf", od.asOf() == null ? null : od.asOf().toString());
        sources.addObject().put("source", AwsEstimatePriceService.SOURCE_SPOT)
                .put("asOf", spotAsOf == null ? null : spotAsOf.toString());
        return new PlanBody.Estimate(profile, monthly, write(sources));
    }

    // ---- snapshot ----

    @Override
    public SnapshotPayload takeSnapshot(GroupRef ref, ReadOnlyCloudView view) {
        CloudAccount account = account(ref.cloudAccountId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        String[] parts = AwsEligibilityEvaluator.split(ref.groupKey());
        AwsReadOnlyView v = viewFor(account, parts[0], view);
        AutoScalingGroup raw = statusReader.describeGroup(v, parts[1])
                .orElseThrow(() -> new AwsReadException("DescribeAutoScalingGroups", ErrorKind.NOT_FOUND));
        AwsGroupFacts g = AwsGroupFacts.from(parts[0], raw);
        return AwsSnapshotCodec.encode(parts[0], raw, AwsSnapshotCodec.Context.none(),
                g.hasLegacyTag() && !g.hasMarker());
    }

    /**
     * D-23b-01. A restore program that ran {@link com.xammer.cloud.service.autospotting.aws.steps.RestoreShapeStep}
     * to DONE removed XamOps' configuration (the shape, the types it added, and the maintenance policy it owned, which
     * the restore clears or keeps as the customer's), so those markers go; the remembered {@code allowBurstable}
     * choice stays. Without that step (a drain-only revert, or a failure before the restore) the configuration is still
     * on the group: the markers stay and {@link PlanOptions#CONFIG_ON_GROUP} records it.
     */
    /** D-23b-03 review #6: {@code keepLinkedSnapshot} is for the ENABLE's own snapshot step, never stored. */
    @Override
    public String desiredConfigFromTarget(String targetConfigJson) {
        if (targetConfigJson == null || !targetConfigJson.contains(KEEP_LINKED_SNAPSHOT)) {
            return targetConfigJson;
        }
        try {
            JsonNode n = JSON.readTree(targetConfigJson);
            if (n instanceof ObjectNode o && o.remove(KEEP_LINKED_SNAPSHOT) != null) {
                return write(o);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // unreadable target: written as is, like before
        }
        return targetConfigJson;
    }

    @Override
    public String desiredConfigAfterRestore(String desiredConfigJson, java.util.Set<String> doneSteps) {
        if (desiredConfigJson == null || desiredConfigJson.isBlank()) {
            return desiredConfigJson;
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(desiredConfigJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return desiredConfigJson;
        }
        if (!(parsed instanceof ObjectNode n)) {
            return desiredConfigJson;
        }
        boolean restored = doneSteps != null
                && doneSteps.contains(com.xammer.cloud.service.autospotting.aws.steps.RestoreShapeStep.NAME);
        if (!restored) {
            if (n.path(PlanOptions.CONFIG_ON_GROUP).asBoolean(false)) {
                return desiredConfigJson;
            }
            n.put(PlanOptions.CONFIG_ON_GROUP, true);
            return write(n);
        }
        boolean changed = n.remove(PlanOptions.CONFIG_ON_GROUP) != null;
        if (n.remove(KEEP_LINKED_SNAPSHOT) != null) {
            changed = true;
        }
        if (AwsMaintenancePolicy.owns(n)) {
            n.remove(AwsMaintenancePolicy.KEY);
            changed = true;
        }
        if (n.path("options") instanceof ObjectNode opts) {
            if (opts.remove("burstableAdded") != null) {
                changed = true;
            }
            if (opts.remove("burstableRemoved") != null) {
                changed = true;
            }
        }
        return changed ? write(n) : desiredConfigJson;
    }

    // ---- status ----

    @Override
    public GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view) {
        CloudAccount account = account(group.getCloudAccountId()).orElseThrow(() ->
                new AwsReadException("account", ErrorKind.NOT_FOUND));
        String[] parts = AwsEligibilityEvaluator.split(group.getGroupKey());
        AwsReadOnlyView v = viewFor(account, parts[0], view);
        // Ownership through the same state-aware gate as planning (D-23b-01), so status and plans agree.
        return withFallbackSignals(group, statusReader.read(v, parts[1],
                AwsMaintenancePolicy.owns(PlanOptions.ownershipConfigJson(group))), v, parts[1]);
    }

    @Override
    public String liveFingerprintFor(SpotGroup group, String desiredConfigJson, ReadOnlyCloudView view) {
        CloudAccount account = account(group.getCloudAccountId()).orElseThrow(() ->
                new AwsReadException("account", ErrorKind.NOT_FOUND));
        String[] parts = AwsEligibilityEvaluator.split(group.getGroupKey());
        AwsReadOnlyView v = viewFor(account, parts[0], view);
        // desiredConfigJson is the config a succeeding conversion is about to write (its target), not the stored row,
        // so it is read directly: that target is XamOps' configuration on the group by construction (D-23b-01).
        GroupStatus status = statusReader.read(v, parts[1], AwsMaintenancePolicy.owns(desiredConfigJson));
        return status == null || !status.found() ? null : status.liveFingerprint();
    }

    /**
     * The signals only the sweep can add to the status (design-aws.md §6.1, §6.3): when the shortfall began (carried
     * from the previous observation, so two sweeps are needed), the interruption warnings of the last 30 minutes
     * from {@code spot_group_event}, and, for a FALLBACK group past its dwell, the Spot placement score. A score
     * that cannot be read leaves it out and the exit is time-based (§6.5). Read only.
     */
    private GroupStatus withFallbackSignals(SpotGroup group, GroupStatus status, AwsReadOnlyView view, String name) {
        if (status == null || !status.found() || status.healthJson() == null) {
            return status;
        }
        try {
            Instant now = clock.instant();
            ObjectNode health = (ObjectNode) JSON.readTree(status.healthJson());
            JsonNode capacity = status.capacityJson() == null ? JSON.createObjectNode() : JSON.readTree(status.capacityJson());
            int desired = capacity.path("desired").asInt(0);
            int healthy = health.hasNonNull("healthyInService") ? health.path("healthyInService").asInt(0)
                    : health.path("inService").asInt(0);
            boolean shortNow = healthy < desired;
            Instant since = null;
            if (shortNow) {
                JsonNode prior = group.getHealthState() == null ? null : JSON.readTree(group.getHealthState());
                since = prior == null ? null : instant(prior, "shortfallSince");
                since = since == null ? now : since;
            }
            health.put("observedAt", now.toString());
            health.put("shortfallSince", since == null ? null : since.toString());
            health.put("interruptions30m", interruptions(group, now));
            if (group.getState() == GroupState.FALLBACK && group.getStateChangedAt() != null
                    && !group.getStateChangedAt().plus(fallbackRules.properties().dwell()).isAfter(now)) {
                Integer score = statusReader.placementScore(view, name, desired);
                if (score != null) {
                    health.put("placementScore", score);
                }
            }
            return new GroupStatus(true, status.liveFingerprint(), JSON.writeValueAsString(health),
                    status.capacityJson(), status.foreignActivity());
        } catch (JsonProcessingException | RuntimeException e) {
            return status; // the signals are an addition: the base status stands
        }
    }

    private int interruptions(SpotGroup group, Instant now) {
        if (events == null || group.getId() == null) {
            return 0;
        }
        try {
            return events.interruptionsSince(group.getId(), now.minus(fallbackRules.properties().stormWindow()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- reconcile: a pure decision ----

    @Override
    public List<ReconcileAction> reconcile(SpotGroup group, GroupStatus status) {
        if (status == null || !status.found() || status.healthJson() == null) {
            return List.of();
        }
        GroupState state = group.getState();
        try {
            JsonNode health = JSON.readTree(status.healthJson());
            JsonNode capacity = status.capacityJson() == null ? null : JSON.readTree(status.capacityJson());
            int desired = capacity == null ? 0 : capacity.path("desired").asInt(0);
            List<ReconcileAction> actions = new ArrayList<>();
            // The in-flight shortage rule (design-aws.md §2.5, core §4.7): a switch-paused op makes no polls,
            // so the same rule WAIT_ROLLOUT uses is evaluated here from the status the sweep just read.
            if (state == GroupState.APPLYING && health.hasNonNull("healthyInService")
                    && rollout.shortage(clock.instant(), desired, health.path("healthyInService").asInt(0),
                            instant(health, "spotFailureStreakSince"), instant(health, "lastSpotFailureAt"))) {
                actions.add(ReconcileAction.REQUEST_COMPENSATION);
                actions.add(ReconcileAction.ALERT);
            }
            // Fallback and health decisions (design-aws.md §6.3, §6.5). The core refuses a fallback that the
            // preemption rules do not allow (ENABLE and ADOPT are never preempted), so a shortage during an
            // ENABLE still compensates through the rule above.
            AwsFallbackRules.Decision d = fallbackRules.evaluate(state, group.getPauseReason(),
                    group.getPausedFromState(), group.getConfigMode(), group.getStateChangedAt(),
                    AwsFallbackRules.signals(clock.instant(), status.healthJson(), status.capacityJson()));
            actions.addAll(d.actions());
            return actions;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    // ---- mutating programs: gated ----

    @Override
    public StepProgram executeConversion(OperationContext op) {
        gate.requireOpen();
        if (AwsFallbackPrograms.supports(op.kind())) {
            return fallbackPrograms.program(op);
        }
        return library.program(op);
    }

    @Override
    public StepProgram restore(OperationContext op, SnapshotPayload snapshot) {
        gate.requireOpen();
        return revertPrograms.program(op, snapshot);
    }

    // ---- helpers ----

    private static Instant instant(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private Optional<CloudAccount> account(Long id) {
        return id == null ? Optional.empty() : accounts.findById(id);
    }

    private AwsReadOnlyView viewFor(CloudAccount account, String region, ReadOnlyCloudView passed) {
        if (passed instanceof AwsReadOnlyView v && region.equals(v.region())) {
            return v;
        }
        return views.view(account, region);
    }

    private static ObjectNode spec(AwsGroupFacts.LtSpec s) {
        if (s == null) {
            return null;
        }
        ObjectNode n = JSON.createObjectNode();
        n.put("id", s.id());
        n.put("name", s.name());
        n.put("version", s.version());
        return n;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode a = JSON.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private static String join(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return String.join(",", out);
    }

    private static String str(Integer i) {
        return i == null ? null : String.valueOf(i);
    }

    private static String str(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan serialization failed", e);
        }
    }
}
