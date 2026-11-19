package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
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
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * The GCP {@link SpotProvider} (design-gcp.md §1.3). <strong>Read only in this phase.</strong>
 *
 * <ul>
 *   <li>{@code checkEligibility}, {@code createPlan}, {@code takeSnapshot} and {@code getStatus} read
 *       through a {@link GcpReadOnlyView}; no mutating facade is reachable from them (AC-PLN-01).</li>
 *   <li>The floor mechanism is the two-version MIG, Option A (design-gcp.md §5.2, PO-19): no dependency
 *       on a pre-GA {@code provisioningModelMix}. {@code autospotting.gcp.native-mix-enabled} is read
 *       and recorded in the plan but changes nothing here.</li>
 *   <li>{@code reconcile} is a pure decision and returns no fallback action until Phase 28.</li>
 *   <li>{@code executeConversion} and {@code restore} refuse with {@code AS_PROVIDER_NOT_READY}:
 *       {@code autospotting.gcp.v2-mutations-enabled} and {@code autospotting.gcp.conversions-enabled}
 *       stay false and the programs arrive in Phase 27.</li>
 * </ul>
 * Two {@code CloudAccount} rows on one {@code gcp_project_id}: the lowest id owns the project and a
 * plan for any other is refused with 409 {@code DUPLICATE_PROVIDER_ACCOUNT} (AC-SPN-14).
 */
@Component
public class GcpMigSpotProvider implements SpotProvider {
    static final double HOURS_PER_MONTH = 730.0;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final CloudAccountRepository accounts;
    private final GcpViewSource views;
    private final GcpEligibilityEvaluator evaluator;
    private final GcpMigStatusReader statusReader;
    private final GcpEstimatePriceService prices;
    private final GcpProviderGate gate;
    private final boolean nativeMixEnabled;
    private final boolean autoscaledFloorAllowed;
    private final Clock clock;

    @Autowired
    public GcpMigSpotProvider(CloudAccountRepository accounts, GcpViewSource views, GcpEligibilityEvaluator evaluator,
                              GcpMigStatusReader statusReader, GcpEstimatePriceService prices, GcpProviderGate gate,
                              @Value("${autospotting.gcp.native-mix-enabled:false}") boolean nativeMixEnabled,
                              @Value("${autospotting.gcp.floor.autoscaled-n-allowed:false}") boolean autoscaledFloorAllowed) {
        this(accounts, views, evaluator, statusReader, prices, gate, nativeMixEnabled, autoscaledFloorAllowed,
                Clock.systemUTC());
    }

    GcpMigSpotProvider(CloudAccountRepository accounts, GcpViewSource views, GcpEligibilityEvaluator evaluator,
                       GcpMigStatusReader statusReader, GcpEstimatePriceService prices, GcpProviderGate gate,
                       boolean nativeMixEnabled, boolean autoscaledFloorAllowed, Clock clock) {
        this.accounts = accounts;
        this.views = views;
        this.evaluator = evaluator;
        this.statusReader = statusReader;
        this.prices = prices;
        this.gate = gate;
        this.nativeMixEnabled = nativeMixEnabled;
        this.autoscaledFloorAllowed = autoscaledFloorAllowed;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.GCP;
    }

    // ---- eligibility ----

    @Override
    public EligibilityResult checkEligibility(GroupRef ref, ReadOnlyCloudView view, Profile profile, EvalMode mode) {
        Optional<CloudAccount> account = account(ref.cloudAccountId());
        if (account.isEmpty()) {
            return new EligibilityResult(List.of(EligibilityResult.Check.failed("b-CHECK_FAILED-ACCOUNT",
                    "The connected account could not be found.", ErrorKind.NOT_FOUND)), List.of(), List.of());
        }
        if (mode == EvalMode.PLAN) {
            requireUniqueProject(account.get());
        }
        try {
            GcpReadOnlyView v = viewFor(account.get(), view);
            return evaluator.evaluate(account.get(), v, ref.groupKey(), ref.providerStableId(), null, profile, mode,
                    new HashMap<>()).result();
        } catch (GcpReadException e) {
            return new EligibilityResult(List.of(EligibilityResult.Check.failed("b-CHECK_FAILED-ACCOUNT_READ",
                    "Could not read the project.", e.kind())), List.of(), List.of());
        }
    }

    // ---- plan ----

    @Override
    public PlanBody createPlan(PlanRequest req, ReadOnlyCloudView view) {
        CloudAccount account = account(req.ref().cloudAccountId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        requireUniqueProject(account);
        if (req.kind() == OperationKind.REVERT) {
            // A revert is never blocked by the conversion-only checks (DR-03). Its program arrives in Phase 28.
            ObjectNode revert = JSON.createObjectNode().put("program", "REVERT").put("method", "RESTORE_SNAPSHOT");
            return new PlanBody(null, null, null, null, new EligibilityResult(List.of(), List.of(), List.of()), null,
                    List.of(), null, null, write(revert));
        }
        Profile profile = req.profile() == null ? new Profile("conservative", null) : req.profile();
        GcpReadOnlyView v;
        GcpEligibilityEvaluator.Evaluation ev;
        try {
            v = viewFor(account, view);
            ev = evaluator.evaluate(account, v, req.ref().groupKey(), req.ref().providerStableId(), null, profile,
                    EvalMode.PLAN, new HashMap<>());
        } catch (GcpReadException e) {
            return new PlanBody(null, null, null, null, new EligibilityResult(List.of(EligibilityResult.Check.failed(
                    "b-CHECK_FAILED-ACCOUNT_READ", "Could not read the project.", e.kind())), List.of(), List.of()),
                    null, List.of(), null, null, null);
        }
        GcpMigFacts g = ev.group();
        if (g == null || ev.templates().isEmpty()) {
            return new PlanBody(null, null, null, null, ev.result(), null, List.of(), null, null, null);
        }
        GcpProfiles.ProfileName name = GcpProfiles.parse(profile.id());
        if (name == null) {
            name = GcpProfiles.preselectedDefault();
        }
        Integer customN = GcpEligibilityEvaluator.customFloorOf(profile);
        Integer min = autoscalerBound(ev.autoscaler(), "minNumReplicas");
        Integer max = autoscalerBound(ev.autoscaler(), "maxNumReplicas");
        int t = g.targetSize();
        GcpProfiles.Evaluated chosen = GcpProfiles.evaluate(name, t, customN, min, max);
        SnapshotPayload snapshot = GcpSnapshotCodec.encode(g, ev.autoscaler(), ev.templates(), false, List.of());
        ObjectNode current = currentNode(g, min, max);
        ObjectNode target = targetNode(g, chosen);
        String diff = write(diffNode(g, chosen));
        PlanBody.Rollout rollout = rollout(g, ev, chosen);
        List<PlanBody.Estimate> estimates = estimates(account, g, ev);
        String floor = write(floorNode(g, chosen));
        String revert = write(JSON.createObjectNode().put("program", "REVERT").put("method", "RESTORE_SNAPSHOT")
                .put("exact", snapshot.exact()));
        return new PlanBody(write(current), write(target), diff, snapshot, ev.result(), rollout, estimates, floor, null,
                revert);
    }

    private static Integer autoscalerBound(JsonNode autoscaler, String field) {
        if (autoscaler == null || !autoscaler.path("autoscalingPolicy").hasNonNull(field)) {
            return null;
        }
        return autoscaler.path("autoscalingPolicy").path(field).asInt();
    }

    private static ObjectNode currentNode(GcpMigFacts g, Integer min, Integer max) {
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", "MIG");
        n.put("locationType", g.locationType());
        n.put("location", g.location());
        n.put("name", g.name());
        n.put("instanceTemplate", g.instanceTemplate());
        n.set("versions", g.raw().path("versions").deepCopy());
        n.put("targetSize", g.targetSize());
        n.set("updatePolicy", g.updatePolicy().deepCopy());
        n.set("distributionZones", JSON.valueToTree(g.distributionZones()));
        n.put("autoscaled", g.autoscalerLink() != null);
        n.put("autoscalerMin", min);
        n.put("autoscalerMax", max);
        return n;
    }

    /** Option A, the two-version MIG: {@code xas-standard} fixed at N on the original template, {@code xas-spot} the remainder. */
    private ObjectNode targetNode(GcpMigFacts g, GcpProfiles.Evaluated p) {
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", "MIG");
        n.put("floorMechanism", "TWO_VERSION_MIG");
        ArrayNode versions = n.putArray("versions");
        if (p.floorN() > 0) {
            ObjectNode std = versions.addObject();
            std.put("name", GcpMigFacts.STANDARD_VERSION);
            std.put("instanceTemplate", g.instanceTemplate() != null ? g.instanceTemplate()
                    : g.versions().get(0).instanceTemplate());
            std.putObject("targetSize").put("fixed", p.floorN());
        }
        ObjectNode spot = versions.addObject();
        spot.put("name", GcpMigFacts.SPOT_VERSION);
        spot.put("instanceTemplate", "(XamOps Spot copy, created at enable)");
        spot.put("remainder", true);
        ObjectNode policy = n.putObject("updatePolicy");
        policy.put("type", "PROACTIVE");
        policy.put("minimalAction", "REPLACE");
        policy.put("mostDisruptiveAllowedAction", "REPLACE");
        policy.put("replacementMethod", "SUBSTITUTE");
        policy.putObject("maxSurge").put("fixed", surge(g));
        policy.putObject("maxUnavailable").put("fixed", 0);
        n.put("restoreCustomerUpdatePolicy", true);
        // PO-20: the single attribution label on XamOps templates, removed on revert.
        n.putObject("templateLabels").put("xamops-autospotting", "<group id assigned at enable>");
        return n;
    }

    private static int surge(GcpMigFacts g) {
        return g.regional() ? Math.max(1, g.zoneCount()) : 1;
    }

    private static ArrayNode diffNode(GcpMigFacts g, GcpProfiles.Evaluated p) {
        ArrayNode diff = JSON.createArrayNode();
        addDiff(diff, "versions", String.valueOf(g.versions().size()), p.floorN() > 0 ? "2" : "1");
        addDiff(diff, "standardFloor", null, String.valueOf(p.floorN()));
        addDiff(diff, "spotVersion", null, GcpMigFacts.SPOT_VERSION);
        addDiff(diff, "updatePolicy.type", g.updatePolicy().path("type").asText(null), "PROACTIVE (restored after the rollout)");
        return diff;
    }

    private static void addDiff(ArrayNode diff, String field, String from, String to) {
        if (java.util.Objects.equals(from, to)) {
            return;
        }
        diff.addObject().put("field", field).put("from", from).put("to", to);
    }

    private PlanBody.Rollout rollout(GcpMigFacts g, GcpEligibilityEvaluator.Evaluation ev, GcpProfiles.Evaluated p) {
        int surge = surge(g);
        ObjectNode q = JSON.createObjectNode();
        q.put("surgeVms", surge);
        if (ev.shape() != null) {
            q.put("extraVcpu", surge * ev.shape().guestCpus());
        }
        if (ev.region() != null) {
            ArrayNode metrics = q.putArray("metrics");
            for (JsonNode m : ev.region().path("quotas")) {
                String metric = m.path("metric").asText();
                if ("CPUS".equals(metric) || "PREEMPTIBLE_CPUS".equals(metric)
                        || (ev.shape() != null && metric.equals(ev.shape().series().toUpperCase(java.util.Locale.ROOT) + "_CPUS"))) {
                    metrics.addObject().put("metric", metric).put("limit", m.path("limit").asDouble())
                            .put("usage", m.path("usage").asDouble());
                }
            }
        }
        q.put("asOf", clock.instant().toString());
        String headroom = surge + " extra VM(s)" + (ev.shape() == null ? "" : " (" + surge * ev.shape().guestCpus() + " vCPU)")
                + " above the target size during the rollout";
        return new PlanBody.Rollout("SURGE_FIRST_PROACTIVE_REPLACE", p.atCurrent().spot(), surge, "5 min to 1 h",
                headroom, write(q));
    }

    private ObjectNode floorNode(GcpMigFacts g, GcpProfiles.Evaluated p) {
        ObjectNode f = JSON.createObjectNode();
        f.put("mechanism", "TWO_VERSION_MIG");
        f.put("profile", p.name().name());
        f.put("floorN", p.floorN());
        f.put("fullSpot", p.name() == GcpProfiles.ProfileName.FULL_SPOT);
        f.set("atCurrent", counts(p.atCurrent()));
        f.set("atAutoscalerMin", counts(p.atMin()));
        f.set("atAutoscalerMax", counts(p.atMax()));
        f.put("autoscaled", g.autoscalerLink() != null);
        f.put("autoscaledFloorAllowed", autoscaledFloorAllowed);
        f.put("nativeMixEnabled", nativeMixEnabled);
        f.put("nativeMixUsed", false);
        return f;
    }

    private static JsonNode counts(GcpProfiles.Counts c) {
        if (c == null) {
            return JSON.nullNode();
        }
        return JSON.createObjectNode().put("standard", c.standard()).put("spot", c.spot());
    }

    // ---- estimates (design-gcp.md §6.4) ----

    private List<PlanBody.Estimate> estimates(CloudAccount account, GcpMigFacts g, GcpEligibilityEvaluator.Evaluation ev) {
        List<PlanBody.Estimate> out = new ArrayList<>();
        Integer min = autoscalerBound(ev.autoscaler(), "minNumReplicas");
        Integer max = autoscalerBound(ev.autoscaler(), "maxNumReplicas");
        for (GcpProfiles.Evaluated p : GcpProfiles.evaluateNamed(g.targetSize(), min, max)) {
            String name = p.name().name().toLowerCase(java.util.Locale.ROOT);
            if (p.blocked()) {
                out.add(new PlanBody.Estimate(name, 0.0, "[]"));
                continue;
            }
            out.add(estimate(account, g, ev, name, p.atCurrent().spot()));
        }
        return out;
    }

    /**
     * Monthly savings = spot VMs x (On-Demand - Spot) x 730 h, priced from the Billing Catalog. Any input
     * that is not priced makes the figure null ("Not priced"), never a partial sum and never 0 (AC-PLN-09).
     */
    PlanBody.Estimate estimate(CloudAccount account, GcpMigFacts g, GcpEligibilityEvaluator.Evaluation ev,
                               String profile, int spotCount) {
        Optional<String> type = machineTypeOf(ev);
        if (type.isEmpty() || g.anyZone() == null || g.region() == null) {
            return new PlanBody.Estimate(profile, null, "[]");
        }
        EstimatePriceService.Platform platform = platformOf(ev);
        PriceKey key = new PriceKey(g.region(), g.anyZone(), type.get(), platform, "Shared");
        PricedRate od = prices.onDemand(account, key);
        PricedRate spot = prices.spot(account, key);
        if (!od.priced() || !spot.priced()) {
            return new PlanBody.Estimate(profile, null, "[]");
        }
        BigDecimal perVm = od.ratePerHour().subtract(spot.ratePerHour()).multiply(BigDecimal.valueOf(HOURS_PER_MONTH));
        double monthly = perVm.multiply(BigDecimal.valueOf(spotCount)).setScale(2, RoundingMode.HALF_UP).doubleValue();
        ArrayNode sources = JSON.createArrayNode();
        sources.addObject().put("source", od.source()).put("asOf", od.asOf() == null ? null : od.asOf().toString());
        sources.addObject().put("source", spot.source()).put("asOf", spot.asOf() == null ? null : spot.asOf().toString());
        return new PlanBody.Estimate(profile, monthly, write(sources));
    }

    /** The machine type of the MIG's template, from its {@code properties.machineType} (never defaulted). */
    static Optional<String> machineTypeOf(GcpEligibilityEvaluator.Evaluation ev) {
        return ev.templates().stream().map(GcpSnapshotCodec.TemplateRead::resource).filter(r -> r != null)
                .map(r -> r.path("properties").path("machineType").asText(null)).filter(m -> m != null && !m.isBlank())
                .map(GcpMachineShapes::shortName).findFirst();
    }

    /** Linux when the boot image is a plain public image; any licensed or unknown image is not priced. */
    static EstimatePriceService.Platform platformOf(GcpEligibilityEvaluator.Evaluation ev) {
        for (GcpSnapshotCodec.TemplateRead t : ev.templates()) {
            if (t.resource() == null) {
                continue;
            }
            for (JsonNode d : t.resource().path("properties").path("disks")) {
                if (!d.path("boot").asBoolean(false)) {
                    continue;
                }
                String image = d.path("initializeParams").path("sourceImage").asText("").toLowerCase(java.util.Locale.ROOT);
                if (image.isEmpty() || image.contains("windows") || image.contains("sql") || image.contains("rhel")
                        || image.contains("sles") || image.contains("suse")) {
                    return EstimatePriceService.Platform.UNKNOWN;
                }
                return EstimatePriceService.Platform.LINUX;
            }
        }
        return EstimatePriceService.Platform.UNKNOWN;
    }

    // ---- snapshot ----

    @Override
    public SnapshotPayload takeSnapshot(GroupRef ref, ReadOnlyCloudView view) {
        CloudAccount account = account(ref.cloudAccountId()).orElseThrow(() ->
                new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Account not found."));
        GcpReadOnlyView v = viewFor(account, view);
        GcpMigStatusReader.Read read = statusReader.readForSnapshot(v, ref.groupKey());
        return GcpSnapshotCodec.encode(read.group(), read.autoscaler(), read.templates(), false, List.of());
    }

    // ---- status ----

    @Override
    public GroupStatus getStatus(SpotGroup group, ReadOnlyCloudView view) {
        CloudAccount account = account(group.getCloudAccountId()).orElseThrow(() ->
                new GcpReadException("account", ErrorKind.NOT_FOUND));
        return statusReader.read(viewFor(account, view), group.getGroupKey());
    }

    // ---- reconcile: a pure decision ----

    @Override
    public List<ReconcileAction> reconcile(SpotGroup group, GroupStatus status) {
        // No fallback or health actions until Phase 28: the GCP health rules arrive with the fallback programs.
        return List.of();
    }

    // ---- mutating programs: refused ----

    @Override
    public StepProgram executeConversion(OperationContext op) {
        gate.requireProgram();
        return null;
    }

    @Override
    public StepProgram restore(OperationContext op, SnapshotPayload snapshot) {
        gate.requireProgram();
        return null;
    }

    // ---- helpers ----

    private Optional<CloudAccount> account(Long id) {
        return id == null ? Optional.empty() : accounts.findById(id);
    }

    private GcpReadOnlyView viewFor(CloudAccount account, ReadOnlyCloudView passed) {
        if (passed instanceof GcpReadOnlyView v && v.project().equals(account.getGcpProjectId())) {
            return v;
        }
        return views.view(account);
    }

    /** AC-SPN-14: the lowest-id CloudAccount owns a project; a plan for another row on it is a 409. */
    private void requireUniqueProject(CloudAccount account) {
        String project = account.getGcpProjectId();
        if (project == null || project.isBlank() || account.getId() == null) {
            return;
        }
        List<CloudAccount> rows = accounts.findByAwsAccountIdOrGcpProjectId(project, project);
        Optional<Long> owner = rows.stream().filter(a -> project.equals(a.getGcpProjectId())).map(CloudAccount::getId)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder());
        if (rows.stream().filter(a -> project.equals(a.getGcpProjectId())).count() > 1
                && owner.isPresent() && !owner.get().equals(account.getId())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "DUPLICATE_PROVIDER_ACCOUNT",
                    "Another connected account already uses this GCP project.");
        }
    }

    private static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan serialization failed", e);
        }
    }
}
