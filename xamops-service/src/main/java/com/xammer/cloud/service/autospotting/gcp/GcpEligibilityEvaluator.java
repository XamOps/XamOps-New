package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.aws.Sig;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The single evaluator behind list, plan and confirm for GCP (design-gcp.md §6, FR-ELG-01), run in
 * LIST, PLAN or CONFIRM mode (design-core.md §9.2). It reads through a {@link GcpReadOnlyView}: no
 * mutating call is reachable.
 *
 * <p>Blockers are produced in the §6.1 order. A read that fails, for any {@link ErrorKind}, is a
 * BLOCKED {@code CHECK_FAILED} naming the check, never a pass (AC-ELG-03); only a confirmed 404 of
 * the MIG is {@code GCP_MIG_NOT_FOUND}. LIST runs what the MIG resource answers plus one template read
 * per distinct self-link per request; the rest comes from the 24 h pass cache or is {@code DEFERRED}.
 *
 * <p>Hard blockers beyond §6.1 as the program decided them: {@code GCP_ACCELERATORS} and
 * {@code GCP_LOCAL_SSD} (the data and GPU state a preemption loses), and {@code GCP_NO_READINESS_GATE}
 * (PD-02, C-16). IaC-owned MIGs get the acknowledged warning {@code GCP_IAC_MARKERS}.
 */
@Component
public class GcpEligibilityEvaluator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ZONE = Pattern.compile("^[a-z]+-[a-z]+[0-9]+-[a-z]$");
    private static final List<String> MANAGED_SERVICE_LABEL_PREFIXES = List.of(
            "goog-dataproc-", "goog-batch-", "goog-composer-", "goog-vertex-", "goog-ml-");
    private static final Set<String> IAC_LABELS = Set.of("goog-terraform-provisioned", "managed-by-cnrm",
            "goog-pulumi-provisioned", "goog-config-connector");
    private static final List<String> DISRUPTION_ORDER = List.of("NONE", "REFRESH", "RESTART", "REPLACE");
    /** The custom-role permissions XamOps needs (design-gcp.md §10.2). */
    static final List<String> REQUIRED_PERMISSIONS = List.of(
            "compute.instanceGroupManagers.get", "compute.instanceTemplates.get", "compute.regions.get",
            "compute.instanceTemplates.create", "compute.instanceTemplates.delete",
            "compute.instanceTemplates.useReadOnly", "compute.instanceGroupManagers.update");
    static final String ACT_AS = "iam.serviceAccounts.actAs";

    private final GcpViewSource views;
    private final GcpMachineShapes shapes;
    private final EligibilityCache cache;
    private final AutoSpottingMetrics metrics;
    private final boolean autoscaledFloorAllowed;
    private final TtlCache<String, Set<String>> lbGroups;

    @Autowired
    public GcpEligibilityEvaluator(GcpViewSource views, GcpMachineShapes shapes, EligibilityCache cache,
                                   AutoSpottingMetrics metrics,
                                   @Value("${autospotting.gcp.floor.autoscaled-n-allowed:false}") boolean autoscaledFloorAllowed) {
        this(views, shapes, cache, metrics, autoscaledFloorAllowed, Clock.systemUTC());
    }

    GcpEligibilityEvaluator(GcpViewSource views, GcpMachineShapes shapes, EligibilityCache cache,
                            AutoSpottingMetrics metrics, boolean autoscaledFloorAllowed, Clock clock) {
        this.views = views;
        this.shapes = shapes;
        this.cache = cache;
        this.metrics = metrics;
        this.autoscaledFloorAllowed = autoscaledFloorAllowed;
        this.lbGroups = new TtlCache<>(Duration.ofHours(24), clock);
    }

    /** What an evaluation read, so plan creation reuses it without a second pass. */
    public record Evaluation(EligibilityResult result, GcpMigFacts group, JsonNode autoscaler,
                             List<GcpSnapshotCodec.TemplateRead> templates, GcpMachineShapes.Shape shape,
                             JsonNode region, String fingerprint, String planHash) {
    }

    /** The parts of a group key {@code project/location/name}. */
    public record Key(String project, String location, String name) {
        public boolean zonal() {
            return ZONE.matcher(location).matches();
        }
    }

    public static Key split(String groupKey) {
        String[] p = groupKey == null ? new String[0] : groupKey.split("/", -1);
        if (p.length != 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) {
            throw new IllegalArgumentException("groupKey must be project/location/name: " + groupKey);
        }
        return new Key(p[0], p[1], p[2]);
    }

    /** Evaluate a group by key; the MIG is read here. */
    public Evaluation evaluate(CloudAccount account, String groupKey, String stableId, Profile profile, EvalMode mode) {
        return evaluate(account, views.view(account), groupKey, stableId, null, profile, mode, new HashMap<>());
    }

    /**
     * Evaluate with a MIG the caller already listed ({@code described}) and a per-request template memo, so a
     * list reads each distinct template self-link once (design-gcp.md §6.3).
     */
    public Evaluation evaluate(CloudAccount account, GcpReadOnlyView view, String groupKey, String stableId,
                               JsonNode described, Profile profile, EvalMode mode,
                               Map<String, Sig<JsonNode>> templateMemo) {
        Key key = split(groupKey);
        List<Check> blockers = new ArrayList<>();
        List<Check> warnings = new ArrayList<>();
        List<Check> deferred = new ArrayList<>();
        // 1. GCP_MIG_NOT_FOUND: only a confirmed 404 (or an id that no longer matches).
        JsonNode raw = described;
        if (raw == null) {
            try {
                raw = view.reads().getMig(!key.zonal(), key.location(), key.name());
            } catch (RuntimeException e) {
                ErrorKind kind = GcpErrorMapper.classify(e);
                if (kind == ErrorKind.NOT_FOUND) {
                    blockers.add(blocker("GCP_MIG_NOT_FOUND", "The instance group no longer exists."));
                } else {
                    blockers.add(Check.failed("b-CHECK_FAILED-MIG_READ", "Could not read the instance group.", kind));
                }
                return finish(account, mode, blockers, warnings, deferred, null, null, List.of(), null, null, "none", "none");
            }
        }
        GcpMigFacts g = GcpMigFacts.from(key.project(), raw);
        String fp = GcpFingerprint.of(g);
        String planHash = GcpFingerprint.planHash(g);
        if (stableId != null && g.id() != null && !stableId.equals(g.id())) {
            blockers.add(blocker("GCP_MIG_NOT_FOUND", "The instance group was replaced by another with the same name."));
            return finish(account, mode, blockers, warnings, deferred, g, null, List.of(), null, null, fp, planHash);
        }
        // 2. GCP_LOCATION_UNKNOWN.
        if (!g.locationKnown() || (g.regional() && g.distributionZones().isEmpty())) {
            blockers.add(blocker("GCP_LOCATION_UNKNOWN", "The instance group's location could not be determined."));
            return finish(account, mode, blockers, warnings, deferred, g, null, List.of(), null, null, fp, planHash);
        }
        boolean fresh = mode != EvalMode.LIST;
        Profile p = profile == null ? new Profile("conservative", null) : profile;
        GcpProfiles.ProfileName profileName = GcpProfiles.parse(p.id());
        Integer customN = customFloor(p);

        // Templates: one read per distinct self-link (memoised per request in LIST).
        List<GcpSnapshotCodec.TemplateRead> templates = new ArrayList<>();
        Map<String, JsonNode> readTemplates = new HashMap<>();
        ErrorKind templateFailure = null;
        for (String link : g.templateLinks()) {
            Sig<JsonNode> sig = templateMemo.computeIfAbsent(link, l -> attempt(() -> view.reads().getInstanceTemplate(l)));
            if (sig instanceof Sig.Ok<JsonNode> ok) {
                readTemplates.put(link, ok.value());
                templates.add(new GcpSnapshotCodec.TemplateRead(link, ok.value()));
            } else if (sig instanceof Sig.Failed<JsonNode> f) {
                templateFailure = templateFailure == null ? f.kind() : templateFailure;
                templates.add(new GcpSnapshotCodec.TemplateRead(link, null));
            }
        }
        List<JsonNode> tpls = new ArrayList<>(readTemplates.values());
        boolean templatesOk = templateFailure == null && !tpls.isEmpty();

        // 3. GCP_GKE_NODE_POOL: label first, then the gke- prefix.
        if (!templatesOk) {
            blockers.add(Check.failed("b-CHECK_FAILED-TEMPLATE_READ", "Could not read the instance template.",
                    templateFailure == null ? ErrorKind.VALIDATION : templateFailure));
        } else if (anyLabel(tpls, "goog-gke-node"::equals) || g.name().startsWith("gke-")
                || tpls.stream().anyMatch(t -> t.path("name").asText("").startsWith("gke-"))) {
            blockers.add(blocker("GCP_GKE_NODE_POOL",
                    "This instance group belongs to a GKE node pool, which GKE manages. It can't be converted here."));
        }
        // 4. GCP_MANAGED_SERVICE.
        if (templatesOk && anyLabel(tpls, k -> MANAGED_SERVICE_LABEL_PREFIXES.stream().anyMatch(k::startsWith))) {
            blockers.add(blocker("GCP_MANAGED_SERVICE",
                    "This instance group is managed by another Google Cloud service (Dataproc, Batch or similar)."));
        }
        // 5. GCP_STATEFUL: the policy and status always; per-instance configs in PLAN and CONFIRM.
        Sig<List<JsonNode>> perInstance = Sig.deferred();
        if (fresh) {
            perInstance = attempt(() -> view.reads().listPerInstanceConfigs(g.regional(), g.location(), g.name()));
        }
        if (g.hasStatefulConfig() || (perInstance instanceof Sig.Ok<List<JsonNode>> ok && !ok.value().isEmpty())) {
            blockers.add(blocker("GCP_STATEFUL", "This instance group keeps per-instance state (stateful MIG)."));
        } else if (perInstance instanceof Sig.Failed<List<JsonNode>> f) {
            blockers.add(Check.failed("b-CHECK_FAILED-GCP_STATEFUL", "Could not run the GCP_STATEFUL check.", f.kind()));
        } else if (perInstance instanceof Sig.Deferred) {
            deferred.add(deferred("GCP_STATEFUL"));
        }
        // 6. GCP_SIZE_TOO_SMALL: target size, plus the autoscaler minimum when it is read.
        Sig<JsonNode> autoscaler = Sig.deferred();
        String link = g.autoscalerLink();
        if (link != null && fresh) {
            autoscaler = attempt(() -> view.reads().getAutoscaler(link));
        }
        JsonNode autoscalerNode = autoscaler instanceof Sig.Ok<JsonNode> a ? a.value() : null;
        Integer minReplicas = autoscalerNode == null ? null
                : autoscalerNode.path("autoscalingPolicy").hasNonNull("minNumReplicas")
                ? autoscalerNode.path("autoscalingPolicy").path("minNumReplicas").asInt() : null;
        if (g.targetSize() <= 1 || (minReplicas != null && minReplicas <= 1)) {
            blockers.add(blocker("GCP_SIZE_TOO_SMALL",
                    "This instance group is too small to keep a standard floor and a Spot VM (needs at least 2)."));
        }
        if (autoscaler instanceof Sig.Failed<JsonNode> f) {
            blockers.add(Check.failed("b-CHECK_FAILED-AUTOSCALER", "Could not read the autoscaler.", f.kind()));
        } else if (link != null && autoscaler instanceof Sig.Deferred) {
            deferred.add(deferred("GCP_AUTOSCALER"));
        }
        // 7. GCP_SHARED_CORE_UNSUPPORTED (PD-09), from machineTypes.get.
        JsonNode primary = primaryTemplate(g, readTemplates);
        String machineType = primary == null ? null : GcpMachineShapes.shortName(primary.path("properties").path("machineType").asText(null));
        Sig<GcpMachineShapes.Shape> shapeSig = Sig.deferred();
        if (machineType != null) {
            if (fresh) {
                final String mt = machineType;
                shapeSig = attempt(() -> shapes.shape(view, g.anyZone(), mt));
            } else if (cache.isPassCached(account.getId(), groupKey, "GCP_SHARED_CORE_UNSUPPORTED", fp)) {
                shapeSig = Sig.deferred();
            }
        }
        if (shapeSig instanceof Sig.Ok<GcpMachineShapes.Shape> s) {
            if (s.value().sharedCoreUnsupported()) {
                blockers.add(blocker("GCP_SHARED_CORE_UNSUPPORTED",
                        "This shared-core machine type (" + machineType + ") has no billed vCPU on file, so it can't be priced."));
            } else {
                cache.recordPass(account.getId(), groupKey, "GCP_SHARED_CORE_UNSUPPORTED", fp);
            }
        } else if (shapeSig instanceof Sig.Failed<GcpMachineShapes.Shape> f) {
            blockers.add(Check.failed("b-CHECK_FAILED-GCP_SHARED_CORE_UNSUPPORTED",
                    "Could not run the GCP_SHARED_CORE_UNSUPPORTED check.", f.kind()));
        } else if (machineType != null && !cache.isPassCached(account.getId(), groupKey, "GCP_SHARED_CORE_UNSUPPORTED", fp)) {
            deferred.add(deferred("GCP_SHARED_CORE_UNSUPPORTED"));
        }
        // 8. GCP_MULTI_VERSION_FOREIGN.
        if (g.versions().size() > 1 && !g.isXamopsPair()) {
            blockers.add(blocker("GCP_MULTI_VERSION_FOREIGN",
                    "This instance group runs more than one template version that XamOps did not create."));
        }
        // 9. GCP_UPDATE_IN_PROGRESS.
        if (!g.versionTargetReached() || !g.stable()) {
            blockers.add(blocker("GCP_UPDATE_IN_PROGRESS", "An update is in progress on this instance group. Try again when it is stable."));
        }
        // 10. GCP_STANDBY_POOL.
        if (g.targetStoppedSize() > 0 || g.targetSuspendedSize() > 0) {
            blockers.add(blocker("GCP_STANDBY_POOL", "This instance group keeps stopped or suspended standby VMs."));
        }
        // 11. GCP_MACHINE_UNSUPPORTED (A4X and bare metal), from the type name.
        if (machineType != null && (machineType.startsWith("a4x-") || machineType.contains("-metal"))) {
            blockers.add(blocker("GCP_MACHINE_UNSUPPORTED", "This machine type (" + machineType + ") isn't available as Spot."));
        }
        // 12. GCP_DISRUPTION_POLICY.
        String disruptive = g.mostDisruptiveAllowedAction();
        if (disruptive != null && DISRUPTION_ORDER.indexOf(disruptive) >= 0
                && DISRUPTION_ORDER.indexOf(disruptive) < DISRUPTION_ORDER.indexOf("REPLACE")) {
            blockers.add(blocker("GCP_DISRUPTION_POLICY",
                    "This instance group's update policy doesn't allow replacing VMs (mostDisruptiveAllowedAction=" + disruptive + ")."));
        }
        // 13. GCP_TEMPLATE_UNPRESERVABLE.
        if (templatesOk && tpls.stream().anyMatch(GcpEligibilityEvaluator::unpreservable)) {
            blockers.add(blocker("GCP_TEMPLATE_UNPRESERVABLE",
                    "The template uses a specific reservation or sole-tenant node affinity that a Spot copy can't keep."));
        }
        // Hard blockers the program added: accelerators and local SSD.
        if (templatesOk && tpls.stream().anyMatch(t -> t.path("properties").path("guestAccelerators").size() > 0)) {
            blockers.add(blocker("GCP_ACCELERATORS", "The template attaches accelerators (GPUs), which a Spot copy would lose on preemption."));
        }
        if (templatesOk && tpls.stream().anyMatch(GcpEligibilityEvaluator::hasLocalSsd)) {
            blockers.add(blocker("GCP_LOCAL_SSD", "The template uses local SSD, whose data is lost on preemption."));
        }
        // 14. GCP_QUOTA_SURGE (regions.get).
        JsonNode region = null;
        if (fresh && shapeSig instanceof Sig.Ok<GcpMachineShapes.Shape> shapeOk) {
            Sig<JsonNode> regionSig = attempt(() -> view.reads().getRegion(g.region()));
            if (regionSig instanceof Sig.Ok<JsonNode> r) {
                region = r.value();
                String quotaProblem = quotaProblem(region, shapeOk.value(), g);
                if (quotaProblem != null) {
                    blockers.add(blocker("GCP_QUOTA_SURGE", quotaProblem));
                }
            } else if (regionSig instanceof Sig.Failed<JsonNode> f) {
                blockers.add(Check.failed("b-CHECK_FAILED-GCP_QUOTA_SURGE", "Could not run the GCP_QUOTA_SURGE check.", f.kind()));
            }
        } else if (!fresh) {
            deferred.add(deferred("GCP_QUOTA_SURGE"));
        }
        // 15. GCP_PERMISSION: custom-role permissions plus actAs on each template service account.
        if (fresh) {
            Sig<List<String>> missing = attempt(() -> missingPermissions(view, tpls));
            if (missing instanceof Sig.Ok<List<String>> m && !m.value().isEmpty()) {
                blockers.add(blocker("GCP_PERMISSION", "XamOps is missing permissions on this project: "
                        + String.join(", ", m.value()) + "."));
            } else if (missing instanceof Sig.Failed<List<String>> f) {
                blockers.add(Check.failed("b-CHECK_FAILED-GCP_PERMISSION", "Could not run the GCP_PERMISSION check.", f.kind()));
            }
        } else {
            deferred.add(deferred("GCP_PERMISSION"));
        }
        // 15b. GCP_ROLE_OUTDATED: a checked AutoSpotting project whose custom role lacks part of the current
        // version (xamops-as-role v1 = 14 permissions). Null access type (not checked yet) never runs it.
        if (fresh && roleCheckApplies(account)) {
            Sig<Boolean> outdated = attempt(() -> {
                Set<String> granted = view.reads().testIamPermissions(GcpAccessValidator.currentRolePermissions());
                return !granted.containsAll(GcpAccessValidator.currentRolePermissions());
            });
            if (outdated instanceof Sig.Ok<Boolean> o && o.value()) {
                blockers.add(blocker("GCP_ROLE_OUTDATED", "The XamOps custom role in this project is older than this version "
                        + "expects. Re-run the setup script with --refresh to update it, then plan again."));
            }
        }
        // 16. GCP_ACCOUNT_READ_ONLY: a null access type never blocks reads.
        if (readOnlyAccount(account)) {
            blockers.add(blocker("GCP_ACCOUNT_READ_ONLY", "This project was connected read-only, so XamOps can't change its groups."));
        }
        // 17. GCP_NO_READINESS_GATE (PD-02, C-16): a load-balanced MIG without an autohealing health check.
        Sig<Set<String>> lb = Sig.deferred();
        if (fresh) {
            lb = attempt(() -> lbGroups.get(g.project(), () -> loadBalancedGroups(view)));
        } else if (lbGroups.contains(g.project())) {
            lb = attempt(() -> lbGroups.get(g.project(), () -> loadBalancedGroups(view)));
        }
        boolean loadBalanced = false;
        if (lb instanceof Sig.Ok<Set<String>> l) {
            loadBalanced = l.value().stream().anyMatch(ig -> ig.equals(g.raw().path("instanceGroup").asText(null)));
            if (loadBalanced && !g.hasAutoHealing()) {
                blockers.add(blocker("GCP_NO_READINESS_GATE",
                        "New VMs could receive traffic before they are ready, so capacity behind the load balancer could dip "
                                + "during the rollout. Add an autohealing health check to this MIG, then plan again."));
            }
        } else if (lb instanceof Sig.Failed<Set<String>> f) {
            blockers.add(Check.failed("b-CHECK_FAILED-GCP_NO_READINESS_GATE", "Could not run the GCP_NO_READINESS_GATE check.", f.kind()));
        } else {
            deferred.add(deferred("GCP_NO_READINESS_GATE"));
        }
        // 18. GCP_FLOOR_SHAPE_UNVERIFIED (PO-GCP-A): an autoscaled MIG with a floor N > 0.
        if (profileName == null) {
            blockers.add(Check.failed("b-CHECK_FAILED-PROFILE", "Unknown profile '" + p.id() + "'.", ErrorKind.VALIDATION));
        } else if (link != null && !autoscaledFloorAllowed
                && GcpProfiles.floor(profileName, Math.max(2, g.targetSize()), customN) > 0) {
            blockers.add(blocker("GCP_FLOOR_SHAPE_UNVERIFIED",
                    "This instance group is autoscaled and a standard floor can't be held on it yet."));
        }
        // Warnings (§6.2).
        if (loadBalanced || g.hasAutoHealing()) {
            warnings.add(warning("GCP_LB_OR_AUTOHEAL", "This group uses a load balancer or autohealing. Add the preemption notice handling to your shutdown script.", g.name()));
        }
        if (templatesOk && anyLabel(tpls, IAC_LABELS::contains)) {
            warnings.add(warning("GCP_IAC_MARKERS", "This group looks managed by infrastructure as code. The next apply will undo the conversion.", g.name()));
        }
        if (templatesOk && tpls.stream().anyMatch(t -> t.path("properties").path("resourceManagerTags").size() > 0)) {
            warnings.add(warning("GCP_SECURE_TAGS", "The template carries secure tags that the Spot copy keeps.", g.name()));
        }
        if (!g.regional() || g.distributionZones().size() == 1) {
            warnings.add(warning("GCP_SINGLE_ZONE", "This group runs in a single zone.", g.name()));
        }
        if (g.hasAllInstancesConfig()) {
            warnings.add(warning("GCP_ALL_INSTANCES_CONFIG", "This group's allInstancesConfig overrides stay as they are.", g.name()));
        }
        if (autoscalerNode != null && !"NONE".equals(autoscalerNode.path("autoscalingPolicy").path("cpuUtilization")
                .path("predictiveMethod").asText("NONE"))) {
            warnings.add(warning("GCP_PREDICTIVE_AUTOSCALING", "Predictive autoscaling can pre-warm VMs into a Spot stockout.", g.name()));
        }
        if (profileName != null && g.regional() && g.targetSize() > 1) {
            int floorN = GcpProfiles.floor(profileName, g.targetSize(), customN);
            if (floorN > 0 && floorN < g.zoneCount()) {
                warnings.add(warning("GCP_FLOOR_ZONE_GAP", "The standard floor (" + floorN + ") is smaller than the number of zones ("
                        + g.zoneCount() + "), so some zones hold no standard VM.", g.name()));
            }
        }
        return finish(account, mode, blockers, warnings, deferred, g, autoscalerNode, templates,
                shapeSig instanceof Sig.Ok<GcpMachineShapes.Shape> s ? s.value() : null, region, fp, planHash);
    }

    private Evaluation finish(CloudAccount account, EvalMode mode, List<Check> blockers, List<Check> warnings,
                              List<Check> deferred, GcpMigFacts g, JsonNode autoscaler,
                              List<GcpSnapshotCodec.TemplateRead> templates, GcpMachineShapes.Shape shape,
                              JsonNode region, String fp, String planHash) {
        if (mode == EvalMode.LIST) {
            blockers.forEach(b -> metrics.eligibilityBlocked(Provider.GCP, b.code()));
        }
        return new Evaluation(new EligibilityResult(blockers, warnings, deferred), g, autoscaler, templates, shape,
                region, fp, planHash);
    }

    // ---- helpers ----

    private static JsonNode primaryTemplate(GcpMigFacts g, Map<String, JsonNode> read) {
        for (String link : g.templateLinks()) {
            JsonNode t = read.get(link);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static boolean anyLabel(List<JsonNode> templates, Predicate<String> keyMatches) {
        for (JsonNode t : templates) {
            var it = t.path("properties").path("labels").fieldNames();
            while (it.hasNext()) {
                if (keyMatches.test(it.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean unpreservable(JsonNode t) {
        JsonNode props = t.path("properties");
        return "SPECIFIC_RESERVATION".equals(props.path("reservationAffinity").path("consumeReservationType").asText())
                || props.path("scheduling").path("nodeAffinities").size() > 0;
    }

    private static boolean hasLocalSsd(JsonNode t) {
        for (JsonNode d : t.path("properties").path("disks")) {
            if ("SCRATCH".equals(d.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    /** A quota the surge would exceed, or null. Surge VMs: Z for a regional MIG (k = 1), 1 for a zonal MIG. */
    static String quotaProblem(JsonNode region, GcpMachineShapes.Shape shape, GcpMigFacts g) {
        int surge = g.regional() ? Math.max(1, g.zoneCount()) : 1;
        double extra = (double) surge * shape.guestCpus();
        Map<String, double[]> quotas = new HashMap<>();
        for (JsonNode q : region.path("quotas")) {
            quotas.put(q.path("metric").asText(), new double[] {q.path("limit").asDouble(), q.path("usage").asDouble()});
        }
        List<String> metrics = new ArrayList<>();
        double[] preemptible = quotas.get("PREEMPTIBLE_CPUS");
        metrics.add(preemptible != null && preemptible[0] > 0 ? "PREEMPTIBLE_CPUS" : "CPUS");
        String family = shape.series() == null ? null : shape.series().toUpperCase(Locale.ROOT) + "_CPUS";
        if (family != null && quotas.containsKey(family)) {
            metrics.add(family);
        }
        for (String m : metrics) {
            double[] q = quotas.get(m);
            if (q != null && q[1] + extra > q[0]) {
                return "The rollout needs " + (long) extra + " more vCPU temporarily and region quota " + m + " allows "
                        + (long) Math.max(0, q[0] - q[1]) + ".";
            }
        }
        return null;
    }

    private List<String> missingPermissions(GcpReadOnlyView view, List<JsonNode> templates) {
        Set<String> granted = view.reads().testIamPermissions(REQUIRED_PERMISSIONS);
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (!granted.contains(p)) {
                missing.add(p);
            }
        }
        Set<String> accounts = new java.util.TreeSet<>();
        for (JsonNode t : templates) {
            for (JsonNode sa : t.path("properties").path("serviceAccounts")) {
                String email = sa.path("email").asText(null);
                if (email != null && !email.isBlank() && !"default".equals(email)) {
                    accounts.add(email);
                }
            }
        }
        for (String email : accounts) {
            if (!view.reads().testIamPermissions(email, List.of(ACT_AS)).contains(ACT_AS)) {
                missing.add(ACT_AS + " on " + email);
            }
        }
        return missing;
    }

    private static Set<String> loadBalancedGroups(GcpReadOnlyView view) {
        Set<String> groups = new HashSet<>();
        for (JsonNode bs : view.reads().aggregatedListBackendServices()) {
            for (JsonNode b : bs.path("backends")) {
                String ig = b.path("group").asText(null);
                if (ig != null) {
                    groups.add(ig);
                }
            }
        }
        return groups;
    }

    static boolean roleCheckApplies(CloudAccount account) {
        String t = account.getGcpAccessType();
        return GcpAccessValidator.AUTOSPOTTING.equals(t) || GcpAccessValidator.READ_WRITE.equals(t);
    }
    /** A project connected read-only. A null access type never blocks reads. */
    static boolean readOnlyAccount(CloudAccount account) {
        if (GcpAccessValidator.READ_ONLY.equals(account.getGcpAccessType())) {
            return true;
        }
        String t = account.getAccessType();
        if (t == null) {
            return false;
        }
        String n = t.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return n.equals("read-only") || n.equals("readonly");
    }

    private static Integer customFloor(Profile p) {
        if (p.parametersJson() == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(p.parametersJson());
            if (n.hasNonNull("floor")) {
                return n.path("floor").asInt();
            }
            return n.hasNonNull("n") ? n.path("n").asInt() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static <T> Sig<T> attempt(Supplier<T> read) {
        try {
            return Sig.ok(read.get());
        } catch (RuntimeException e) {
            return Sig.failed(GcpErrorMapper.classify(e));
        }
    }

    /** The SPI profile's parsed name and custom floor, for plan creation. */
    static Optional<GcpProfiles.ProfileName> profileName(Profile profile) {
        return Optional.ofNullable(GcpProfiles.parse(profile == null ? "conservative" : profile.id()));
    }

    static Integer customFloorOf(Profile profile) {
        return profile == null ? null : customFloor(profile);
    }

    private static Check blocker(String code, String text) {
        return Check.of("b-" + code, code, text);
    }

    private static Check warning(String code, String text, String detail) {
        String hash = GcpFingerprint.sha256(code + "|" + detail).substring(0, 8);
        return Check.of("w-" + code.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + hash, code, text);
    }

    private static Check deferred(String code) {
        return Check.of("d-" + code, code, "Checked at plan.");
    }
}
