package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The AWS eligibility rules (design-aws.md §7.2, §7.3), as a pure function of already-read inputs.
 * One rule set serves LIST, PLAN and CONFIRM: only how much was read differs, so the codes and the
 * result shape are the same in every mode (AC-ELG-05). A read that failed becomes a BLOCKED
 * {@code CHECK_FAILED} naming the check and its {@link ErrorKind}, never a pass (AC-ELG-03); a read
 * LIST did not make is {@code DEFERRED}.
 */
public final class AwsEligibilityRules {

    /** The capability baseline: stacks older than this can't convert (design-aws.md §10.1). */
    public static final String MINIMUM_STACK_VERSION = "rw-2026.11.1";
    /** A Spot quota margin under this share of the limit is {@code SPOT_VCPU_QUOTA_LOW}. */
    static final double QUOTA_MARGIN = 0.10;
    /** A deregistration delay above this is {@code DEREG_DELAY_LONG} (seconds). */
    static final int LONG_DEREGISTRATION_DELAY = 120;

    private AwsEligibilityRules() {
    }

    /** What the connection says about the account (known without any AWS call). */
    public record AccountFacts(boolean readOnlyConnection, String stackTemplateVersion, boolean regionCovered) {
    }

    /** A Service Quotas limit, current usage and the need of this conversion (§7.2). */
    public record QuotaInfo(String quotaCode, double limit, double usage, double needed, boolean blocking,
                            String source) {
        public double remaining() {
            return limit - usage;
        }
    }

    /** A lifecycle hook on the group and its heartbeat timeout. */
    public record HookInfo(String name, Integer heartbeatTimeout) {
    }

    /** Type selection results the rules need (§4.4). */
    public record TypeInfo(AwsTypeSelector.Selection selection, boolean xenToNitro,
                           List<String> burstableAlternatives, List<String> burstableSelected) {
        public TypeInfo {
            burstableAlternatives = burstableAlternatives == null ? List.of() : List.copyOf(burstableAlternatives);
            burstableSelected = burstableSelected == null ? List.of() : List.copyOf(burstableSelected);
        }

        public TypeInfo(AwsTypeSelector.Selection selection, boolean xenToNitro) {
            this(selection, xenToNitro, List.of(), List.of());
        }
    }

    /** RI/Savings Plans utilization in scope; null utilization means no commitments (passes). */
    public record CommitmentInfo(Double utilizationPercent, double thresholdPercent) {
    }

    /** The chosen profile: its B/P, or an unknown id that fails closed. */
    public record ProfileChoice(String id, AwsProfiles.ProfileParams params) {
    }

    /** Everything the rules look at. A {@code null} {@code profile} applies no profile-specific rule (list rows). */
    public record Inputs(
            Sig<Optional<AwsGroupFacts>> group,
            AccountFacts account,
            ProfileChoice profile,
            Sig<AwsLaunchInfo> launch,
            Sig<Boolean> ecsManagedTermination,
            Sig<Boolean> refreshInProgress,
            Sig<Boolean> otherFeatureInFlight,
            Sig<List<String>> missingPermissions,
            Sig<QuotaInfo> spotQuota,
            Sig<QuotaInfo> odQuota,
            Sig<List<HookInfo>> lifecycleHooks,
            Sig<Integer> maxDeregistrationDelay,
            Sig<CommitmentInfo> commitment,
            Sig<TypeInfo> types,
            Sig<Boolean> openReservations) {
    }

    /** Evaluate every rule. */
    public static EligibilityResult evaluate(Inputs in) {
        List<Check> blockers = new ArrayList<>();
        List<Check> warnings = new ArrayList<>();
        List<Check> deferred = new ArrayList<>();

        // Account level: known without any call.
        if (in.account().readOnlyConnection()) {
            blockers.add(blocker("READ_ONLY_CONNECTION",
                    "This account is connected read-only, so XamOps can't change its Auto Scaling groups."));
        }
        List<StackCapabilities> missingCapabilities = StackCapabilities.missingForConversion(
                in.account().stackTemplateVersion(), provenByPreflight(in));
        if (!missingCapabilities.isEmpty()) {
            blockers.add(blocker("STACK_TOO_OLD",
                    "The XamOps stack in this account is older than " + MINIMUM_STACK_VERSION
                            + " and lacks: " + StackCapabilities.describe(missingCapabilities)
                            + ". Update the stack, then plan again."));
        }

        AwsGroupFacts g = null;
        if (in.group() instanceof Sig.Failed<Optional<AwsGroupFacts>> f) {
            // The tags that carry the EKS lock could not be read: fail closed, never proceed (F-15).
            blockers.add(Check.failed("b-CHECK_FAILED-GROUP_READ", "Could not read the Auto Scaling group.", f.kind()));
            blockers.add(Check.failed("b-CHECK_FAILED-KUBERNETES_NODES",
                    "Could not check whether this group runs Kubernetes nodes.", f.kind()));
        } else if (in.group() instanceof Sig.Ok<Optional<AwsGroupFacts>> ok) {
            if (ok.value().isEmpty()) {
                blockers.add(blocker("GROUP_NOT_FOUND", "This Auto Scaling group no longer exists."));
            } else {
                g = ok.value().get();
            }
        } else {
            deferred.add(deferred("GROUP_READ"));
        }

        if (g != null) {
            groupRules(g, in, blockers, warnings);
        }
        if (!in.account().regionCovered()) {
            blockers.add(blocker("REGION_NOT_COVERED",
                    "XamOps isn't receiving Auto Scaling events from this region yet. Update the stack to cover it."));
        }

        // The launch template and AMI read.
        final AwsLaunchInfo launch = (g != null && g.launchConfigurationName() == null)
                ? launchRules(g, in, blockers, warnings, deferred) : null;

        // Expensive checks: a value, a failure, or deferred.
        if (g != null) {
            sig("ECS_MANAGED_TERMINATION", in.ecsManagedTermination(), blockers, deferred, v -> {
                if (v) {
                    blockers.add(blocker("ECS_MANAGED_TERMINATION",
                            "This group is an ECS capacity provider with managed termination protection enabled."));
                }
            });
            sig("REFRESH_IN_PROGRESS", in.refreshInProgress(), blockers, deferred, v -> {
                if (v) {
                    blockers.add(blocker("REFRESH_IN_PROGRESS",
                            "An instance refresh is running on this group. Plan again when it finishes."));
                }
            });
            sig("OTHER_FEATURE_IN_FLIGHT", in.otherFeatureInFlight(), blockers, deferred, v -> {
                if (v) {
                    blockers.add(blocker("OTHER_FEATURE_IN_FLIGHT",
                            "EBS automation is updating this group. Retry after it finishes."));
                }
            });
            sig("PERMISSION_MISSING", in.missingPermissions(), blockers, deferred, missing -> {
                if (!missing.isEmpty()) {
                    blockers.add(new Check("b-PERMISSION_MISSING", "PERMISSION_MISSING",
                            "The XamOps role is missing permissions: " + String.join(", ", missing)
                                    + ". Update the stack.", null));
                }
            });
            sig("SPOT_VCPU_QUOTA", in.spotQuota(), blockers, deferred, q -> {
                if (q.needed() > q.remaining()) {
                    blockers.add(blocker("SPOT_VCPU_QUOTA", "The Spot vCPU quota in this region is too low: "
                            + fmt(q.needed()) + " vCPU needed, " + fmt(Math.max(0, q.remaining())) + " available."));
                } else if (q.limit() > 0 && (q.remaining() - q.needed()) < QUOTA_MARGIN * q.limit()) {
                    warnings.add(warning("SPOT_VCPU_QUOTA_LOW",
                            "Other Spot launches in this region may exhaust the quota during the rollout.",
                            fmt(q.remaining()) + "/" + fmt(q.needed())));
                }
            });
            sig("OD_VCPU_QUOTA", in.odQuota(), blockers, deferred, q -> {
                if (q.needed() > q.remaining()) {
                    if (q.blocking()) {
                        blockers.add(blocker("OD_VCPU_QUOTA", "The On-Demand vCPU quota in this region is too low."));
                    } else {
                        warnings.add(warning("OD_VCPU_QUOTA_LOW",
                                "A full fallback or revert of this group would need more On-Demand vCPU than the quota allows.",
                                fmt(q.remaining()) + "/" + fmt(q.needed())));
                    }
                }
            });
            sig("LIFECYCLE_HOOKS", in.lifecycleHooks(), blockers, deferred, hooks -> {
                if (!hooks.isEmpty()) {
                    String names = hooks.stream().map(HookInfo::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
                    warnings.add(warning("LIFECYCLE_HOOKS", hooks.size() + " lifecycle hook"
                            + (hooks.size() == 1 ? "" : "s") + " will run on every Spot replacement.", names));
                }
            });
            sig("DEREG_DELAY_LONG", in.maxDeregistrationDelay(), blockers, deferred, d -> {
                if (d > LONG_DEREGISTRATION_DELAY) {
                    warnings.add(warning("DEREG_DELAY_LONG",
                            "A target group's deregistration delay is " + d + " s, longer than a Spot interruption notice.",
                            String.valueOf(d)));
                }
            });
            sig("COMMITMENT_UTILIZATION", in.commitment(), blockers, deferred, c -> {
                if (c.utilizationPercent() != null && c.utilizationPercent() < c.thresholdPercent()) {
                    warnings.add(warning("COMMITMENT_UTILIZATION",
                            "Reserved Instance or Savings Plans utilization is below " + fmt(c.thresholdPercent())
                                    + "%; moving to Spot may leave commitments unused.",
                            fmt(c.utilizationPercent())));
                }
            });
            sig("OPEN_CAPACITY_RESERVATION", in.openReservations(), blockers, deferred, v -> {
                if (v && (launch == null || launch.reservationPreferenceOpen())) {
                    warnings.add(warning("OPEN_CAPACITY_RESERVATION",
                            "Converted instances will leave paid capacity reservations idle.", "open"));
                }
            });
            if (g.launchConfigurationName() == null) {
                sig("NO_COMPATIBLE_TYPES", in.types(), blockers, deferred, t -> {
                    if (t.selection().noCompatibleTypes()) {
                        blockers.add(blocker("NO_COMPATIBLE_TYPES",
                                "No other instance type is compatible with this group, so Spot can't be diversified."
                                        + burstableHint(t)));
                    } else if (t.selection().fewTypes()) {
                        warnings.add(warning("FEW_TYPES", "Only " + t.selection().compatibleCount()
                                + " compatible instance types were found; Spot capacity may be limited."
                                + burstableHint(t),
                                String.valueOf(t.selection().compatibleCount())));
                    }
                    if (!t.burstableSelected().isEmpty()) {
                        warnings.add(warning("BURSTABLE_CREDITS", "You allowed burstable instance types ("
                                + String.join(", ", t.burstableSelected()) + "). They earn CPU credits while idle "
                                + "and spend them when busy. When the credits run out, a burstable instance is "
                                + "slowed down, or on unlimited mode billed extra for the CPU it uses. Spot "
                                + "instances of these types can behave differently from your current On-Demand "
                                + "ones, so watch CPU credit balance and cost after the change.",
                                String.join(",", t.burstableSelected())));
                    }
                    if (t.xenToNitro()) {
                        warnings.add(warning("XEN_TO_NITRO",
                                "Some candidate types run on a different hypervisor than the current type.", "xen->nitro"));
                    }
                });
            }
        }

        // Profile-specific rules: only when a profile is given and the group is readable.
        if (in.profile() != null && g != null) {
            if (in.profile().params() == null) {
                blockers.add(Check.failed("b-CHECK_FAILED-PROFILE", "Unknown profile '" + in.profile().id() + "'.",
                        ErrorKind.VALIDATION));
            } else if (g.desired() > 1) {
                AwsProfiles.ProfileParams p = in.profile().params();
                if (AwsProfiles.spotCount(g.desired(), p.base(), p.percentAboveBase()) == 0) {
                    blockers.add(blocker("PROFILE_NO_SPOT", "At the current size of " + g.desired()
                            + ", this profile would keep every instance On-Demand. Choose a more aggressive profile."));
                }
            }
        }
        return new EligibilityResult(blockers, warnings, deferred);
    }

    // ---- group rules (answerable from the group describe) ----

    private static void groupRules(AwsGroupFacts g, Inputs in, List<Check> blockers, List<Check> warnings) {
        if (g.desired() <= 1) {
            blockers.add(blocker("DESIRED_TOO_SMALL",
                    "This group has a desired capacity of " + g.desired() + "; at least 2 are needed to convert."));
        }
        if (g.launchConfigurationName() != null) {
            blockers.add(blocker("LAUNCH_CONFIGURATION",
                    "This group uses a launch configuration. Migrate it to a launch template, then plan again."));
        }
        if (kubernetes(g)) {
            blockers.add(blocker("KUBERNETES_NODES", "This group runs Kubernetes nodes, which XamOps doesn't convert."));
        }
        if (g.tags().containsKey("elasticbeanstalk:environment-name")) {
            blockers.add(blocker("ELASTIC_BEANSTALK", "This group belongs to an Elastic Beanstalk environment."));
        }
        if (g.warmPoolConfigured()) {
            blockers.add(blocker("WARM_POOL", "This group has a warm pool, which isn't supported yet."));
        }
        if (!g.hasMarker() && !g.hasLegacyTag() && g.hasSpotShare()) {
            blockers.add(blocker("EXTERNAL_SPOT", "This group already runs part of its capacity on Spot, which you "
                    + "manage; AutoSpotting can't take it over in this version."));
        }
        if ("vcpu".equalsIgnoreCase(g.desiredCapacityType()) || "memory-mib".equalsIgnoreCase(g.desiredCapacityType())) {
            blockers.add(blocker("CAPACITY_TYPE_UNSUPPORTED",
                    "This group counts capacity in " + g.desiredCapacityType() + ", which isn't supported."));
        }
        AwsMaintenancePolicy.Kind policyKind = AwsMaintenancePolicy.classify(g);
        if (policyKind == AwsMaintenancePolicy.Kind.TERMINATE_FIRST) {
            // XamOps never rewrites a customer's policy (AD-08): the customer raises the maximum or removes it.
            blockers.add(blocker("IMP_TERMINATE_FIRST", "The group's instance maintenance policy replaces "
                    + "instances terminate-first (MaxHealthyPercentage=100). Raise the maximum healthy percentage "
                    + "above 100, or remove the maintenance policy, then plan again."));
        }
        boolean lb = !g.targetGroupArns().isEmpty() || !g.loadBalancerNames().isEmpty();
        if (lb && "EC2".equalsIgnoreCase(g.healthCheckType())) {
            blockers.add(blocker("EC2_HEALTH_WITH_LB",
                    "Switch this group to ELB health checks (HealthCheckType=ELB, with a grace period of at least "
                            + "the instance warm-up), then plan again."));
        }
        // Warnings answerable from the describe.
        if (iacOwned(g)) {
            warnings.add(warning("IAC_OWNED",
                    "This group is managed by infrastructure as code; the next apply may revert the conversion and "
                            + "XamOps will show it as drift.", iacMarker(g)));
        }
        boolean ecs = g.tags().containsKey("AmazonECSManaged");
        if (!ecs && (g.newInstancesProtectedFromScaleIn() || g.protectedInstanceCount() > 0)) {
            warnings.add(warning("SCALE_IN_PROTECTION",
                    "Protected instances aren't replaced by rebalancing, so the Spot mix converges slower.",
                    String.valueOf(g.protectedInstanceCount())));
        }
        if (g.desired() > 0 && g.desired() == g.max()) {
            warnings.add(warning("NO_HEADROOM", "The group is at its maximum size, so replacements have no headroom.",
                    String.valueOf(g.max())));
        }
        if (g.availabilityZones().size() == 1) {
            warnings.add(warning("SINGLE_AZ", "The group runs in one Availability Zone, which limits Spot capacity.",
                    g.availabilityZones().iterator().next()));
        }
        if (policyKind == AwsMaintenancePolicy.Kind.ABSENT) {
            // XamOps will set 100/200 and restore "none" at revert; the group can briefly run up to twice its size.
            warnings.add(warning("IMP_OVERSHOOT", "While AWS replaces instances, this group can briefly run up to "
                    + "twice its desired size (" + g.desired() + " extra instance(s)) and the extra instances are "
                    + "billed. XamOps sets this to keep your capacity from dipping, and removes the setting when "
                    + "you revert.", String.valueOf(g.desired())));
        } else if (policyKind == AwsMaintenancePolicy.Kind.CUSTOMER_LOW_MIN) {
            warnings.add(warning("IMP_MIN_HEALTHY_LOW", "Your maintenance policy allows capacity to drop to "
                    + g.minHealthyPercentage() + "% during replacements, so this group can briefly run below its "
                    + "desired size while XamOps changes it. XamOps does not change your policy.",
                    String.valueOf(g.minHealthyPercentage())));
        }
        if (g.defaultInstanceWarmup() == null) {
            warnings.add(warning("NO_INSTANCE_WARMUP", "No default instance warm-up is set, so a new instance is "
                    + "counted as ready as soon as it passes its health checks, with no extra warm-up time. XamOps "
                    + "leaves this setting alone.", "none"));
        }
    }

    /** Names the burstable types a plan with the option on would add (AD-09); empty when there are none. */
    private static String burstableHint(TypeInfo t) {
        if (t.burstableAlternatives().isEmpty()) {
            return "";
        }
        return " This group uses a burstable type. You can allow burstable alternatives ("
                + String.join(", ", t.burstableAlternatives()) + ") and plan again.";
    }

    // ---- launch template and image rules ----

    private static AwsLaunchInfo launchRules(AwsGroupFacts g, Inputs in, List<Check> blockers, List<Check> warnings,
                                             List<Check> deferred) {
        AwsLaunchInfo[] holder = new AwsLaunchInfo[1];
        sig("LAUNCH_TEMPLATE", in.launch(), blockers, deferred, l -> {
            holder[0] = l;
            if (l.marketOptionsSet()) {
                blockers.add(blocker("LT_MARKET_OPTIONS", "The launch template sets a market type, which a mixed "
                        + "instances policy can't use."));
                if (!g.hasMarker() && !g.hasLegacyTag() && "spot".equalsIgnoreCase(l.marketType())) {
                    blockers.add(blocker("EXTERNAL_SPOT", "This group already runs on Spot through its launch "
                            + "template, which you manage; AutoSpotting can't take it over in this version."));
                }
            }
            if (l.tenancyUnsupported()) {
                blockers.add(blocker("TENANCY_UNSUPPORTED", "Spot doesn't run on Dedicated Hosts or dedicated tenancy."));
            }
            if (l.capacityReservationOnly()) {
                blockers.add(blocker("CAPACITY_RESERVATION_ONLY",
                        "The template can only use On-Demand Capacity Reservations, which Spot can't."));
            }
            if (!l.linux()) {
                warnings.add(warning("NON_LINUX", "The AMI is " + l.platformDetails()
                        + "; savings may not be priced.", l.platformDetails()));
            }
            if (l.placementGroupName() != null && !l.placementGroupName().isBlank()) {
                warnings.add(warning("CLUSTER_PLACEMENT", "The template uses a placement group; a cluster placement "
                        + "group keeps the group in one AZ and Spot capacity may be tight.", l.placementGroupName()));
            }
        });
        return holder[0];
    }

    // ---- helpers ----

    private static <T> void sig(String code, Sig<T> sig, List<Check> blockers, List<Check> deferred,
                                java.util.function.Consumer<T> onOk) {
        if (sig instanceof Sig.Ok<T> ok) {
            onOk.accept(ok.value());
        } else if (sig instanceof Sig.Failed<T> f) {
            blockers.add(Check.failed("b-CHECK_FAILED-" + code, "Could not run the " + code + " check.", f.kind()));
        } else {
            deferred.add(deferred(code));
        }
    }

    static boolean kubernetes(AwsGroupFacts g) {
        for (String key : g.tags().keySet()) {
            if (key.equals("eks:cluster-name") || key.equals("eks:nodegroup-name")
                    || key.startsWith("kubernetes.io/cluster/") || key.startsWith("k8s.io/cluster-autoscaler/")
                    || key.startsWith("karpenter.sh/")) {
                return true;
            }
        }
        return g.name() != null && g.name().startsWith("eks-");
    }

    static boolean iacOwned(AwsGroupFacts g) {
        return iacMarker(g) != null;
    }

    static String iacMarker(AwsGroupFacts g) {
        Map<String, String> tags = g.tags();
        if (tags.containsKey("aws:cloudformation:stack-name")) {
            return "cloudformation";
        }
        for (Map.Entry<String, String> e : tags.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            String v = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (k.startsWith("aws:cdk") || k.equals("cdk:path") || k.startsWith("terraform")
                    || k.equals("managedby") && (v.contains("terraform") || v.contains("cdk") || v.contains("pulumi"))) {
                return k.startsWith("terraform") || v.contains("terraform") ? "terraform" : "cdk";
            }
        }
        return null;
    }

    /** True when {@code version} is null, unparseable, or lacks a capability a conversion needs. */
    public static boolean stackTooOld(String version) {
        return !StackCapabilities.missingForConversion(version).isEmpty();
    }
    /** Capabilities a passing pre-flight read proves on an older stack (design-aws.md §10.1). */
    private static java.util.Set<StackCapabilities> provenByPreflight(Inputs in) {
        java.util.Set<StackCapabilities> proven = java.util.EnumSet.noneOf(StackCapabilities.class);
        if (in.missingPermissions() instanceof Sig.Ok<List<String>> ok && ok.value().isEmpty()) {
            proven.add(StackCapabilities.AS_PASSROLE_SCOPED);
        }
        if (in.spotQuota() instanceof Sig.Ok) {
            proven.add(StackCapabilities.AS_QUOTA_READS);
        }
        return proven;
    }
    private static Check blocker(String code, String text) {
        return Check.of("b-" + code, code, text);
    }

    private static Check warning(String code, String text, String detail) {
        String hash = AwsFingerprint.sha256(code + "|" + detail).substring(0, 8);
        return Check.of("w-" + code.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + hash, code, text);
    }

    private static Check deferred(String code) {
        return Check.of("d-" + code, code, "Checked at plan.");
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }
}
