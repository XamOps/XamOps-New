package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The three-way check of a revert (design-aws.md §5.4, FR-REV-02): each owned field is compared across
 * the snapshot S, what XamOps set (desired D) and the live group L, and the result is the one
 * configuration the revert writes. Pure: it reads values and returns values.
 *
 * <ul>
 *   <li>The Spot fields (the distribution, {@code CapacityRebalance} and the overrides XamOps added) always come
 *       from S, accepted or not: removing the Spot configuration is what revert means.</li>
 *   <li>The launch template (id or name) and the customer's own overrides follow the table: L = S writes
 *       nothing new; a field in {@code accepted_fields} keeps L; L = D writes S; L different from both keeps L
 *       and is listed as changed outside XamOps.</li>
 *   <li>The launch template version string is context and is never taken from S: the live string is kept.</li>
 * </ul>
 * Anything that makes an exact restore impossible is returned as a reason, and anything the customer must
 * agree to as an acknowledgement id.
 */
public final class AwsThreeWayDiff {
    public static final String TEMPLATE_REPLACED = "TEMPLATE_REPLACED";
    public static final String TEMPLATE_VERSION_DELETED = "TEMPLATE_VERSION_DELETED";
    public static final String LEGACY_RECONSTRUCTED = "LEGACY_RECONSTRUCTED";
    public static final String ACK_CUSTOMER_OVERRIDES = "CUSTOMER_OVERRIDES_DROPPED";
    public static final String ACK_LEGACY_TARGET = "LEGACY_REVERT_TARGET";
    public static final String CHANGED_OUTSIDE = "CHANGED_OUTSIDE";
    public static final String ACCEPTED_BASELINE = "ACCEPTED_BASELINE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AwsThreeWayDiff() {
    }

    /** One override type. {@code sdk} is the live object, kept verbatim, or null when built from the snapshot. */
    public record Override(String type, String weight, AwsGroupFacts.LtSpec spec, LaunchTemplateOverrides sdk) {
    }

    /** The six {@code InstancesDistribution} fields as the snapshot returned them. */
    public record Distribution(String onDemandAllocationStrategy, Integer base, Integer percentAboveBase,
                               String spotAllocationStrategy, Integer spotInstancePools, String spotMaxPrice) {
    }

    /** What step 3 writes: the owned fields only. */
    public record Target(String shape, AwsGroupFacts.LtSpec template, List<Override> overrides,
                         Distribution distribution, boolean capacityRebalance, JsonNode maintenancePolicy) {
        /** A target that leaves the maintenance policy alone. */
        public Target(String shape, AwsGroupFacts.LtSpec template, List<Override> overrides,
                      Distribution distribution, boolean capacityRebalance) {
            this(shape, template, overrides, distribution, capacityRebalance, null);
        }
        public boolean standalone() {
            return "LAUNCH_TEMPLATE".equals(shape);
        }

        /** The target-node shape the plan and {@link AwsOwnedFields} use. */
        public ObjectNode toNode() {
            ObjectNode n = JSON.createObjectNode();
            n.put("shape", shape);
            ObjectNode lt = n.putObject("launchTemplate");
            lt.put("id", template == null ? null : template.id());
            lt.put("name", template == null ? null : template.name());
            lt.put("version", template == null ? null : template.version());
            ArrayNode types = n.putArray("overrides");
            overrides.forEach(o -> types.add(o.type()));
            if (distribution == null) {
                n.putNull("instancesDistribution");
            } else {
                ObjectNode d = n.putObject("instancesDistribution");
                d.put("onDemandAllocationStrategy", distribution.onDemandAllocationStrategy());
                d.put("onDemandBaseCapacity", distribution.base());
                d.put("onDemandPercentageAboveBaseCapacity", distribution.percentAboveBase());
                d.put("spotAllocationStrategy", distribution.spotAllocationStrategy());
                if (distribution.spotInstancePools() != null
                        && "lowest-price".equals(distribution.spotAllocationStrategy())) {
                    d.put("spotInstancePools", distribution.spotInstancePools());
                }
            }
            n.put("capacityRebalance", capacityRebalance);
            if (maintenancePolicy != null) {
                n.set(AwsMaintenancePolicy.KEY, maintenancePolicy);
            }
            return n;
        }
    }

    /** A field kept at its live value, with why. */
    public record Kept(String field, String why) {
    }

    /**
     * The outcome. {@code inexactReasons} make the restore not exact (empty means exact); {@code acks} are the
     * acknowledgement ids the customer must give; {@code customerOverrides} are the override types a standalone
     * restore would drop; {@code versionDrift} is true when only the launch template version string moved.
     */
    public record Result(Target target, List<Kept> kept, List<String> inexactReasons, List<String> acks,
                         List<String> customerOverrides, boolean versionDrift) {
        public boolean exact() {
            return inexactReasons.isEmpty();
        }
    }

    /**
     * @param snapshot             the decoded owned block of the stored snapshot
     * @param desired              the configuration XamOps last set ({@code desired_config}, target-node shape), or null
     * @param live                 the live group as described now
     * @param accepted             {@code accepted_fields}: {@code [{field, value, acceptedAt, planId}]}, or null
     * @param snapshotTemplateGone the snapshot's launch template or pinned version no longer exists (DR-5)
     */
    public static Result compute(AwsSnapshotCodec.Owned snapshot, JsonNode desired, AutoScalingGroup live,
                                 JsonNode accepted, boolean snapshotTemplateGone) {
        AwsGroupFacts l = AwsGroupFacts.from("", live);
        List<Override> liveOverrides = liveOverrides(live);
        if (AwsSnapshotCodec.CAPTURED_RECONSTRUCTED.equals(snapshot.capturedFrom())) {
            return legacy(l, liveOverrides);
        }
        Set<String> acceptedNames = acceptedNames(accepted);
        boolean standaloneSnapshot = "LAUNCH_TEMPLATE".equals(snapshot.shape());
        AwsGroupFacts.LtSpec snapshotTemplate = snapshotTemplate(snapshot);
        List<Override> snapshotOverrides = standaloneSnapshot ? List.of()
                : snapshotOverrides(snapshot.mixedInstancesPolicy());
        List<Kept> keptList = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> acks = new ArrayList<>();
        boolean versionDrift = false;

        // ---- launch template: not a Spot field, so it follows the table ----
        String sId = snapshotTemplate == null ? null : snapshotTemplate.idOrName();
        String lId = l.launchTemplate() == null ? null : l.launchTemplate().idOrName();
        String dId = desiredTemplateId(desired);
        AwsGroupFacts.LtSpec template;
        if (Objects.equals(sId, lId)) {
            template = l.launchTemplate();
            if (snapshotTemplate != null && l.launchTemplate() != null
                    && !Objects.equals(snapshotTemplate.version(), l.launchTemplate().version())) {
                versionDrift = true; // context only: the live version string stays (EBS automation, F-18)
            }
            if (snapshotTemplateGone) {
                template = new AwsGroupFacts.LtSpec(l.launchTemplate().id(), l.launchTemplate().name(), "$Default");
                reasons.add(TEMPLATE_VERSION_DELETED);
                acks.add(TEMPLATE_VERSION_DELETED);
            }
        } else if (!acceptedNames.contains("launchTemplate") && Objects.equals(dId, lId) && snapshotTemplate != null) {
            template = snapshotTemplate; // L = D: nobody else touched it, so S
            if (snapshotTemplateGone) {
                template = new AwsGroupFacts.LtSpec(snapshotTemplate.id(), snapshotTemplate.name(), "$Default");
                reasons.add(TEMPLATE_VERSION_DELETED);
                acks.add(TEMPLATE_VERSION_DELETED);
            }
        } else {
            template = l.launchTemplate() != null ? l.launchTemplate() : snapshotTemplate;
            keptList.add(new Kept("launchTemplate",
                    acceptedNames.contains("launchTemplate") ? ACCEPTED_BASELINE : CHANGED_OUTSIDE));
            reasons.add(TEMPLATE_REPLACED);
        }

        // ---- overrides: the customer's own follow the table, the ones XamOps added are a Spot field ----
        List<String> snapshotTypes = types(snapshotOverrides);
        List<String> desiredTypes = desiredTypes(desired);
        List<String> addedByXamOps = desiredTypes.stream().filter(t -> !snapshotTypes.contains(t)).toList();
        List<String> customerAdded = new ArrayList<>();
        for (Override o : liveOverrides) {
            if (!snapshotTypes.contains(o.type()) && !desiredTypes.contains(o.type())) {
                customerAdded.add(o.type());
            }
        }
        List<Override> overrides = new ArrayList<>();
        List<String> customerOverrides = new ArrayList<>();
        if (standaloneSnapshot) {
            customerOverrides.addAll(customerAdded);
            if (!customerAdded.isEmpty()) {
                acks.add(ACK_CUSTOMER_OVERRIDES);
            }
        } else {
            for (Override o : liveOverrides) {
                if (!addedByXamOps.contains(o.type())) {
                    overrides.add(o);
                }
            }
            if (overrides.isEmpty()) {
                overrides.addAll(snapshotOverrides);
            }
            boolean customerRemoved = snapshotTypes.stream()
                    .anyMatch(t -> liveOverrides.stream().noneMatch(o -> o.type().equals(t)));
            if (!customerAdded.isEmpty() || customerRemoved) {
                keptList.add(new Kept("overrides",
                        acceptedNames.contains("overrides") ? ACCEPTED_BASELINE : CHANGED_OUTSIDE));
            }
        }

        // ---- Spot fields: always S ----
        Distribution distribution = standaloneSnapshot ? null : distributionOf(snapshot.mixedInstancesPolicy());
        JsonNode policy = policyRestore(snapshot, desired, l, acceptedNames, keptList);
        Target target = new Target(standaloneSnapshot ? "LAUNCH_TEMPLATE" : "MIXED_INSTANCES_POLICY", template,
                overrides, distribution, snapshot.capacityRebalance(), policy);
        return new Result(target, List.copyOf(keptList), List.copyOf(new LinkedHashSet<>(reasons)),
                List.copyOf(new LinkedHashSet<>(acks)), List.copyOf(customerOverrides), versionDrift);
    }

    /**
     * The maintenance policy a revert writes (design-aws.md AD-08, §5.4), or null to leave it alone. It is written
     * only when XamOps owned it (the desired configuration says so) and the snapshot recorded the original:
     * L equal to S writes nothing; L equal to what XamOps set (100/200) writes S, and "absent" is -1/-1; any other
     * live value was changed outside XamOps and is kept, and listed.
     */
    static JsonNode policyRestore(AwsSnapshotCodec.Owned snapshot, JsonNode desired, AwsGroupFacts live,
                                  Set<String> acceptedNames, List<Kept> kept) {
        if (!AwsMaintenancePolicy.owns(desired) || !snapshot.hasMaintenancePolicy()) {
            return null;
        }
        ObjectNode saved = AwsPriorConfig.restoreNode(snapshot.instanceMaintenancePolicy());
        Integer sMin = AwsMaintenancePolicy.normalize(saved.get("min").asInt());
        Integer sMax = AwsMaintenancePolicy.normalize(saved.get("max").asInt());
        if (Objects.equals(sMin, live.minHealthyPercentage()) && Objects.equals(sMax, live.maxHealthyPercentage())) {
            return saved; // already the original: the check passes, the request repeats it harmlessly
        }
        boolean xamopsValue = Objects.equals(live.minHealthyPercentage(), AwsMaintenancePolicy.OWNED_MIN)
                && Objects.equals(live.maxHealthyPercentage(), AwsMaintenancePolicy.OWNED_MAX);
        if (xamopsValue && !acceptedNames.contains(AwsMaintenancePolicy.KEY)) {
            return saved;
        }
        kept.add(new Kept(AwsMaintenancePolicy.KEY,
                acceptedNames.contains(AwsMaintenancePolicy.KEY) ? ACCEPTED_BASELINE : CHANGED_OUTSIDE));
        return null;
    }

    /** The launch template the snapshot recorded, whichever shape it had. */
    public static AwsGroupFacts.LtSpec snapshotTemplate(AwsSnapshotCodec.Owned snapshot) {
        if ("LAUNCH_TEMPLATE".equals(snapshot.shape()) || snapshot.mixedInstancesPolicy() == null) {
            return snapshot.launchTemplate();
        }
        return specOf(snapshot.mixedInstancesPolicy().path("launchTemplate").path("spec"));
    }

    /** True when the snapshot itself ran Spot: a policy whose On-Demand share is below 100 (§5.3 step 1 skip). */
    public static boolean snapshotHadSpot(AwsSnapshotCodec.Owned snapshot) {
        if (snapshot == null || snapshot.mixedInstancesPolicy() == null) {
            return false;
        }
        Distribution d = distributionOf(snapshot.mixedInstancesPolicy());
        return d != null && d.percentAboveBase() != null && d.percentAboveBase() < 100;
    }

    /**
     * The target for a legacy group, whose snapshot is reconstructed (§5.4 last row): a standalone template when
     * the policy has a single override and no customer-set base, else the policy at B=0, P=100. Always inexact.
     */
    static Result legacy(AwsGroupFacts l, List<Override> liveOverrides) {
        Target target;
        if (!l.mixedInstancesPolicy()) {
            target = new Target("LAUNCH_TEMPLATE", l.launchTemplate(), List.of(), null, l.capacityRebalance());
        } else if (liveOverrides.size() == 1 && (l.onDemandBase() == null || l.onDemandBase() == 0)) {
            target = new Target("LAUNCH_TEMPLATE", l.launchTemplate(), List.of(), null, l.capacityRebalance());
        } else {
            target = new Target("MIXED_INSTANCES_POLICY", l.launchTemplate(), liveOverrides,
                    new Distribution(l.onDemandAllocationStrategy(), 0, 100, l.spotAllocationStrategy(),
                            l.spotInstancePools(), l.spotMaxPrice()), l.capacityRebalance());
        }
        return new Result(target, List.of(), List.of(LEGACY_RECONSTRUCTED), List.of(ACK_LEGACY_TARGET), List.of(), false);
    }

    // ---- readers ----

    static List<Override> liveOverrides(AutoScalingGroup g) {
        List<Override> out = new ArrayList<>();
        if (g.mixedInstancesPolicy() != null && g.mixedInstancesPolicy().launchTemplate() != null) {
            for (LaunchTemplateOverrides o : g.mixedInstancesPolicy().launchTemplate().overrides()) {
                if (o.instanceType() != null) {
                    out.add(new Override(o.instanceType(), o.weightedCapacity(), null, o));
                }
            }
        }
        return out;
    }

    private static List<Override> snapshotOverrides(JsonNode mip) {
        List<Override> out = new ArrayList<>();
        mip.path("launchTemplate").path("overrides").forEach(ov -> {
            if (ov.hasNonNull("type")) {
                out.add(new Override(ov.get("type").asText(), ov.hasNonNull("weight") ? ov.get("weight").asText() : null,
                        ov.path("ltSpec").isObject() ? specOf(ov.get("ltSpec")) : null, null));
            }
        });
        return out;
    }

    private static Distribution distributionOf(JsonNode mip) {
        JsonNode d = mip.path("instancesDistribution");
        if (!d.isObject()) {
            return null;
        }
        return new Distribution(text(d, "onDemandAllocationStrategy"), integer(d, "onDemandBaseCapacity"),
                integer(d, "onDemandPercentageAboveBaseCapacity"), text(d, "spotAllocationStrategy"),
                integer(d, "spotInstancePools"), text(d, "spotMaxPrice"));
    }

    private static AwsGroupFacts.LtSpec specOf(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new AwsGroupFacts.LtSpec(text(n, "id"), text(n, "name"), text(n, "version"));
    }

    private static String desiredTemplateId(JsonNode desired) {
        if (desired == null) {
            return null;
        }
        JsonNode lt = desired.path("launchTemplate");
        if (!lt.isObject()) {
            return null;
        }
        return lt.hasNonNull("id") ? lt.get("id").asText() : text(lt, "name");
    }

    private static List<String> desiredTypes(JsonNode desired) {
        List<String> out = new ArrayList<>();
        if (desired != null && desired.path("overrides").isArray()) {
            desired.get("overrides").forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static List<String> types(List<Override> overrides) {
        return overrides.stream().map(Override::type).toList();
    }

    private static Set<String> acceptedNames(JsonNode accepted) {
        Set<String> out = new LinkedHashSet<>();
        if (accepted != null && accepted.isArray()) {
            accepted.forEach(n -> {
                if (n.hasNonNull("field")) {
                    out.add(n.get("field").asText());
                } else if (n.isTextual()) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Integer integer(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }
}
