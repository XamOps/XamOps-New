package com.xammer.cloud.service.autospotting.aws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.HookInfo;
import com.xammer.cloud.service.autospotting.aws.AwsEligibilityRules.QuotaInfo;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.ec2.model.CapacityReservation;
import software.amazon.awssdk.services.ec2.model.DescribeCapacityReservationsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeCapacityReservationsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypeOfferingsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstanceTypesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.InstanceTypeInfo;
import software.amazon.awssdk.services.ec2.model.InstanceTypeOffering;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion;
import software.amazon.awssdk.services.ec2.model.LocationType;
import software.amazon.awssdk.services.ec2.model.ResponseLaunchTemplateData;
import software.amazon.awssdk.services.ecs.model.CapacityProvider;
import software.amazon.awssdk.services.ecs.model.DescribeCapacityProvidersResponse;
import software.amazon.awssdk.services.iam.model.ContextEntry;
import software.amazon.awssdk.services.iam.model.ContextKeyTypeEnum;
import software.amazon.awssdk.services.iam.model.EvaluationResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The AWS reads behind eligibility checks and plan creation (design-aws.md §4.2, §7). Every method
 * is a {@code Describe}/{@code Get}/{@code List}/{@code Simulate} call through an
 * {@link AwsReadOnlyView}; a failure surfaces as an {@link AwsReadException} naming the call, never
 * as a default value. Launch template, AMI, instance type and offering reads are cached 24 h
 * (an alias version such as {@code $Default} 1 h), so a list's cost is bounded by distinct templates,
 * not groups (NFR-04).
 */
@Component
public class AwsPlanReader {

    static final String SPOT_QUOTA_CODE = "L-34B43A08";
    static final String OD_QUOTA_CODE = "L-1216C47A";
    /** Capacity Rebalancing can overshoot by about 10% (design-aws.md §11.2). */
    static final double REBALANCE_OVERSHOOT = 1.1;
    static final int MAX_PAGES = 20;

    private final AwsEstimatePriceService prices;
    private final Cache<String, AwsLaunchInfo> launchCache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofHours(24)).build();
    private final Cache<String, AwsLaunchInfo> aliasLaunchCache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, List<AwsTypeSelector.TypeFacts>> typesCache = Caffeine.newBuilder()
            .maximumSize(2_000).expireAfterWrite(Duration.ofHours(24)).build();
    private final Cache<String, Map<String, Set<String>>> offeringsCache = Caffeine.newBuilder()
            .maximumSize(2_000).expireAfterWrite(Duration.ofHours(24)).build();

    public AwsPlanReader(AwsEstimatePriceService prices) {
        this.prices = prices;
    }

    /** Selected types plus the figures plan creation needs. */
    public record TypePlan(AwsTypeSelector.Selection selection, boolean xenToNitro, int originalVcpu,
                           Map<String, BigDecimal> spotMedianByType) {
    }

    // ---- launch template and AMI ----

    /** Read the template version a spec resolves to and its AMI. Cached 24 h per (account, region, spec, version). */
    public AwsLaunchInfo launch(CloudAccount account, AwsReadOnlyView view, AwsGroupFacts.LtSpec spec) {
        if (spec == null) {
            throw new AwsReadException("DescribeLaunchTemplateVersions", ErrorKind.VALIDATION);
        }
        String version = spec.version() == null || spec.version().isBlank() ? "$Default" : spec.version();
        boolean alias = version.startsWith("$");
        String key = account.getAwsAccountId() + "|" + view.region() + "|" + spec.idOrName() + "|" + version;
        Cache<String, AwsLaunchInfo> cache = alias ? aliasLaunchCache : launchCache;
        AwsLaunchInfo hit = cache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        AwsLaunchInfo info = AwsErrors.read("DescribeLaunchTemplateVersions", () -> {
            DescribeLaunchTemplateVersionsRequest.Builder rb = DescribeLaunchTemplateVersionsRequest.builder()
                    .versions(version);
            if (spec.id() != null) {
                rb.launchTemplateId(spec.id());
            } else {
                rb.launchTemplateName(spec.name());
            }
            List<LaunchTemplateVersion> versions = view.ec2().describeLaunchTemplateVersions(rb.build())
                    .launchTemplateVersions();
            if (versions.isEmpty()) {
                throw new AwsReadException("DescribeLaunchTemplateVersions", ErrorKind.NOT_FOUND);
            }
            LaunchTemplateVersion v = versions.get(0);
            ResponseLaunchTemplateData d = v.launchTemplateData();
            Image image = null;
            if (d.imageId() != null && !d.imageId().isBlank()) {
                image = AwsErrors.read("DescribeImages", () -> view.ec2()
                        .describeImages(DescribeImagesRequest.builder().imageIds(d.imageId()).build())
                        .images().stream().findFirst().orElse(null));
            }
            return toInfo(v, d, image);
        });
        cache.put(key, info);
        return info;
    }

    static AwsLaunchInfo toInfo(LaunchTemplateVersion v, ResponseLaunchTemplateData d, Image image) {
        var market = d.instanceMarketOptions();
        var placement = d.placement();
        var cr = d.capacityReservationSpecification();
        var profile = d.iamInstanceProfile();
        int ephemeral = (int) d.blockDeviceMappings().stream()
                .filter(b -> b.virtualName() != null && b.virtualName().startsWith("ephemeral")).count();
        boolean efa = d.networkInterfaces().stream().anyMatch(n -> "efa".equalsIgnoreCase(n.interfaceType()));
        String arch = image == null ? null : image.architectureAsString();
        String boot = image == null ? null : image.bootModeAsString();
        if (boot == null && arch != null) {
            boot = "arm64".equals(arch) ? "uefi" : "legacy-bios";
        }
        String sha = AwsFingerprint.sha256(String.valueOf(v.versionNumber()) + "|" + d.imageId() + "|"
                + d.instanceTypeAsString() + "|" + (market != null) + "|"
                + (placement == null ? "" : placement.tenancyAsString()));
        return new AwsLaunchInfo(
                v.versionNumber() == null ? null : v.versionNumber().intValue(), sha,
                d.instanceTypeAsString(), d.imageId(),
                market != null, market == null ? null : market.marketTypeAsString(),
                placement == null ? null : placement.tenancyAsString(),
                placement == null ? null : placement.hostResourceGroupArn(),
                placement == null ? null : placement.groupName(),
                cr == null ? null : cr.capacityReservationPreferenceAsString(),
                cr != null && cr.capacityReservationTarget() != null,
                profile == null ? null : profile.arn(), profile == null ? null : profile.name(),
                ephemeral, efa, d.networkInterfaces().size(),
                d.hibernationOptions() != null && Boolean.TRUE.equals(d.hibernationOptions().configured()),
                arch, boot, image != null && Boolean.TRUE.equals(image.enaSupport()),
                image == null ? null : image.platformDetails(),
                image == null ? null : image.usageOperation());
    }

    // ---- single-signal checks ----

    /** True when an ECS capacity provider wraps this group with managed termination protection enabled. */
    public boolean ecsManagedTermination(AwsReadOnlyView view, String asgArn) {
        return AwsErrors.read("DescribeCapacityProviders", () -> {
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                final String next = token;
                DescribeCapacityProvidersResponse resp = view.ecs()
                        .describeCapacityProviders(r -> r.nextToken(next));
                for (CapacityProvider cp : resp.capacityProviders()) {
                    var p = cp.autoScalingGroupProvider();
                    if (p != null && asgArn != null && asgArn.equals(p.autoScalingGroupArn())
                            && "ENABLED".equals(p.managedTerminationProtectionAsString())) {
                        return true;
                    }
                }
                token = resp.nextToken();
                if (token == null) {
                    break;
                }
            }
            return false;
        });
    }

    /** True when an instance refresh is pending or running (DR-6). */
    public boolean refreshInProgress(AwsReadOnlyView view, String groupName) {
        return AwsErrors.read("DescribeInstanceRefreshes", () -> view.autoScaling()
                .describeInstanceRefreshes(r -> r.autoScalingGroupName(groupName)).instanceRefreshes().stream()
                .anyMatch(r -> AwsGroupStatusReader.ACTIVE_REFRESH.contains(r.statusAsString())));
    }

    /** The group's lifecycle hooks. */
    public List<HookInfo> lifecycleHooks(AwsReadOnlyView view, String groupName) {
        return AwsErrors.read("DescribeLifecycleHooks", () -> view.autoScaling()
                .describeLifecycleHooks(r -> r.autoScalingGroupName(groupName)).lifecycleHooks().stream()
                .map(h -> new HookInfo(h.lifecycleHookName(), h.heartbeatTimeout())).toList());
    }

    /** The longest {@code deregistration_delay.timeout_seconds} across the attached target groups (0 when none). */
    public int maxDeregistrationDelay(AwsReadOnlyView view, List<String> targetGroupArns) {
        int max = 0;
        for (String arn : targetGroupArns) {
            int d = AwsErrors.read("DescribeTargetGroupAttributes", () -> view.elbv2()
                    .describeTargetGroupAttributes(r -> r.targetGroupArn(arn)).attributes().stream()
                    .filter(a -> "deregistration_delay.timeout_seconds".equals(a.key()))
                    .mapToInt(a -> parseInt(a.value())).max().orElse(0));
            max = Math.max(max, d);
        }
        return max;
    }

    /** True when active capacity reservations match the template's instance type with free capacity. */
    public boolean openReservations(AwsReadOnlyView view, String instanceType) {
        if (instanceType == null) {
            return false;
        }
        return AwsErrors.read("DescribeCapacityReservations", () -> {
            DescribeCapacityReservationsResponse resp = view.ec2().describeCapacityReservations(
                    DescribeCapacityReservationsRequest.builder().filters(
                            Filter.builder().name("instance-type").values(instanceType).build(),
                            Filter.builder().name("state").values("active").build()).build());
            for (CapacityReservation c : resp.capacityReservations()) {
                if (c.availableInstanceCount() != null && c.availableInstanceCount() > 0) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * The actions of the WRITE session the stored role must allow (design-aws.md §7.2 PERMISSION_MISSING),
     * simulated against the role's own ARN. Returns the actions that are not allowed.
     */
    public List<String> missingPermissions(AwsReadOnlyView view, String roleArn, AwsLaunchInfo launch) {
        List<String> missing = new ArrayList<>();
        List<String> actions = List.of("autoscaling:UpdateAutoScalingGroup", "autoscaling:CreateOrUpdateTags",
                "autoscaling:DeleteTags");
        List<EvaluationResult> results = AwsErrors.read("SimulatePrincipalPolicy", () -> view.iam()
                .simulatePrincipalPolicy(r -> r.policySourceArn(roleArn).actionNames(actions).resourceArns("*"))
                .evaluationResults());
        for (EvaluationResult r : results) {
            if (!"allowed".equals(r.evalDecisionAsString())) {
                missing.add(r.evalActionName());
            }
        }
        if (launch != null && launch.instanceProfileName() != null) {
            String profileName = launch.instanceProfileName();
            String passRoleArn = AwsErrors.read("GetInstanceProfile", () -> view.iam()
                    .getInstanceProfile(r -> r.instanceProfileName(profileName)).instanceProfile().roles().stream()
                    .map(role -> role.arn()).findFirst().orElse(null));
            if (passRoleArn != null) {
                ContextEntry ctx = ContextEntry.builder().contextKeyName("iam:PassedToService")
                        .contextKeyType(ContextKeyTypeEnum.STRING).contextKeyValues("ec2.amazonaws.com").build();
                List<EvaluationResult> pass = AwsErrors.read("SimulatePrincipalPolicy", () -> view.iam()
                        .simulatePrincipalPolicy(r -> r.policySourceArn(roleArn).actionNames("iam:PassRole")
                                .resourceArns(passRoleArn).contextEntries(ctx)).evaluationResults());
                for (EvaluationResult r : pass) {
                    if (!"allowed".equals(r.evalDecisionAsString())) {
                        missing.add("iam:PassRole");
                    }
                }
            }
        }
        return missing;
    }

    // ---- quotas ----

    /** The Spot vCPU quota check: limit, current usage, and the {@code ceil(spot × 1.1) × vCPU} this conversion needs. */
    public QuotaInfo spotQuota(AwsReadOnlyView view, int spotTarget, int vcpuPerInstance) {
        double needed = Math.ceil(spotTarget * REBALANCE_OVERSHOOT) * vcpuPerInstance;
        double limit = limit(view, SPOT_QUOTA_CODE);
        Double usage = metricUsage(view, "Standard/Spot");
        String source = "cloudwatch";
        if (usage == null) {
            usage = instanceVcpuSum(view);
            source = "describe-instances";
        }
        return new QuotaInfo(SPOT_QUOTA_CODE, limit, usage, needed, true, source);
    }

    /** The On-Demand vCPU quota check against what a full fallback or revert of this group would need. */
    public QuotaInfo onDemandQuota(AwsReadOnlyView view, int spotTarget, int vcpuPerInstance) {
        double needed = Math.ceil(spotTarget * REBALANCE_OVERSHOOT) * vcpuPerInstance;
        double limit = limit(view, OD_QUOTA_CODE);
        Double usage = metricUsage(view, "Standard/OnDemand");
        if (usage == null) {
            throw new AwsReadException("GetMetricData", ErrorKind.TRANSIENT);
        }
        return new QuotaInfo(OD_QUOTA_CODE, limit, usage, needed, false, "cloudwatch");
    }

    private double limit(AwsReadOnlyView view, String code) {
        return AwsErrors.read("GetServiceQuota", () -> {
            Double v = view.serviceQuotas().getServiceQuota(r -> r.serviceCode("ec2").quotaCode(code)).quota().value();
            if (v == null) {
                throw new AwsReadException("GetServiceQuota", ErrorKind.NOT_FOUND);
            }
            return v;
        });
    }

    /** Latest {@code AWS/Usage ResourceCount} for the vCPU class, or null when CloudWatch has no datapoint or fails. */
    private Double metricUsage(AwsReadOnlyView view, String clazz) {
        try {
            Instant now = Instant.now();
            MetricDataQuery q = MetricDataQuery.builder().id("u").returnData(true)
                    .metricStat(MetricStat.builder().period(300).stat("Maximum").metric(Metric.builder()
                            .namespace("AWS/Usage").metricName("ResourceCount").dimensions(
                                    dim("Type", "Resource"), dim("Resource", "vCPU"),
                                    dim("Service", "EC2"), dim("Class", clazz)).build()).build()).build();
            List<MetricDataResult> results = view.cloudWatch().getMetricData(GetMetricDataRequest.builder()
                    .startTime(now.minus(Duration.ofMinutes(30))).endTime(now).metricDataQueries(q).build())
                    .metricDataResults();
            for (MetricDataResult r : results) {
                if (!r.values().isEmpty()) {
                    return r.values().get(0);
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null; // falls back to DescribeInstances for Spot; fails closed for On-Demand
        }
    }

    private static Dimension dim(String n, String v) {
        return Dimension.builder().name(n).value(v).build();
    }

    private double instanceVcpuSum(AwsReadOnlyView view) {
        return AwsErrors.read("DescribeInstances", () -> {
            double sum = 0;
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                final String next = token;
                DescribeInstancesResponse resp = view.ec2().describeInstances(DescribeInstancesRequest.builder()
                        .filters(Filter.builder().name("instance-lifecycle").values("spot").build(),
                                Filter.builder().name("instance-state-name").values("running").build())
                        .nextToken(next).build());
                for (var res : resp.reservations()) {
                    for (var i : res.instances()) {
                        if (i.cpuOptions() != null && i.cpuOptions().coreCount() != null
                                && i.cpuOptions().threadsPerCore() != null) {
                            sum += (double) i.cpuOptions().coreCount() * i.cpuOptions().threadsPerCore();
                        }
                    }
                }
                token = resp.nextToken();
                if (token == null) {
                    break;
                }
            }
            return sum;
        });
    }

    // ---- type selection (§4) ----

    /** Read candidate types, offerings and Spot prices, then select (§4.3-§4.4). */
    public TypePlan types(CloudAccount account, AwsReadOnlyView view, AwsGroupFacts g, AwsLaunchInfo launch,
                          AwsTypeSelector selector) {
        String originalType = launch.instanceType();
        if (originalType == null && !g.instances().isEmpty()) {
            originalType = g.instances().get(0).type();
        }
        if (originalType == null) {
            throw new AwsReadException("DescribeInstanceTypes", ErrorKind.VALIDATION);
        }
        final String original = originalType;
        List<AwsTypeSelector.TypeFacts> originals = describeTypes(view, List.of(original), null);
        if (originals.isEmpty()) {
            throw new AwsReadException("DescribeInstanceTypes", ErrorKind.NOT_FOUND);
        }
        AwsTypeSelector.TypeFacts o = originals.get(0);
        String vcpuKey = view.region() + "|" + account.getAwsAccountId() + "|" + o.vcpu();
        List<AwsTypeSelector.TypeFacts> candidates = typesCache.get(vcpuKey,
                k -> describeTypes(view, null, o.vcpu()));
        Set<String> azs = new TreeSet<>(g.availabilityZones());
        Map<String, Set<String>> offered = offerings(view, azs);
        List<AwsTypeSelector.TypeFacts> withAzs = new ArrayList<>();
        for (AwsTypeSelector.TypeFacts c : candidates) {
            withAzs.add(withAz(c, offered.getOrDefault(c.instanceType(), Set.of()), 0));
        }
        AwsTypeSelector.TypeFacts oWithAz = withAz(o, offered.getOrDefault(o.instanceType(), Set.of(azs.toArray(new String[0]))), 0);
        AwsTypeSelector.ImageFacts image = new AwsTypeSelector.ImageFacts(
                launch.architecture() == null ? "x86_64" : launch.architecture(),
                launch.bootMode() == null ? "legacy-bios" : launch.bootMode(), launch.enaSupport());
        AwsTypeSelector.TemplateFacts template = new AwsTypeSelector.TemplateFacts(launch.ephemeralMappings(),
                launch.efaRequested(), launch.networkInterfaces(), launch.hibernation());
        // Price only the types that survive the hard filter (one batched call, cached 1 h).
        List<String> survivors = new ArrayList<>();
        survivors.add(original);
        for (AwsTypeSelector.TypeFacts c : withAzs) {
            if (!c.instanceType().equals(original) && selector.isCompatible(oWithAz, c, azs, image, template)) {
                survivors.add(c.instanceType());
            }
        }
        var platform = AwsEstimatePriceService.platformOf(launch.platformDetails());
        Map<String, BigDecimal> medians = prices.medianSpotByType(account, view.region(),
                new ArrayList<>(azs), survivors, platform);
        List<AwsTypeSelector.TypeFacts> priced = new ArrayList<>();
        for (AwsTypeSelector.TypeFacts c : withAzs) {
            priced.add(withAz(c, c.offeredAzs(), medians.getOrDefault(c.instanceType(), BigDecimal.valueOf(Double.MAX_VALUE / 4)).doubleValue()));
        }
        AwsTypeSelector.Selection sel = selector.select(withAz(oWithAz, oWithAz.offeredAzs(),
                medians.getOrDefault(original, BigDecimal.ZERO).doubleValue()), azs, image, template, priced);
        Map<String, AwsTypeSelector.TypeFacts> byName = new HashMap<>();
        withAzs.forEach(c -> byName.put(c.instanceType(), c));
        boolean xenToNitro = !o.nitro() && sel.types().stream().filter(t -> !t.equals(original))
                .map(byName::get).anyMatch(c -> c != null && c.nitro());
        return new TypePlan(sel, xenToNitro, o.vcpu(), medians);
    }

    private static AwsTypeSelector.TypeFacts withAz(AwsTypeSelector.TypeFacts t, Set<String> azs, double price) {
        return new AwsTypeSelector.TypeFacts(t.instanceType(), t.vcpu(), t.memoryMib(), t.architectures(),
                t.supportedUsageClasses(), t.burstable(), t.bareMetal(), t.nitro(), t.supportedBootModes(),
                t.acceleratorCount(), t.acceleratorModel(), t.instanceStoreDisks(), t.efaSupported(),
                t.maxNetworkInterfaces(), t.hibernationSupported(), t.currentGeneration(), azs, price);
    }

    private List<AwsTypeSelector.TypeFacts> describeTypes(AwsReadOnlyView view, List<String> types, Integer vcpu) {
        return AwsErrors.read("DescribeInstanceTypes", () -> {
            List<AwsTypeSelector.TypeFacts> out = new ArrayList<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                DescribeInstanceTypesRequest.Builder rb = DescribeInstanceTypesRequest.builder().nextToken(token);
                if (types != null) {
                    rb.instanceTypesWithStrings(types);
                }
                if (vcpu != null) {
                    rb.filters(Filter.builder().name("vcpu-info.default-vcpus").values(String.valueOf(vcpu)).build(),
                            Filter.builder().name("supported-usage-class").values("spot").build());
                }
                DescribeInstanceTypesResponse resp = view.ec2().describeInstanceTypes(rb.build());
                for (InstanceTypeInfo i : resp.instanceTypes()) {
                    out.add(toFacts(i));
                }
                token = resp.nextToken();
                if (token == null) {
                    break;
                }
            }
            return out;
        });
    }

    static AwsTypeSelector.TypeFacts toFacts(InstanceTypeInfo i) {
        int gpus = i.gpuInfo() == null ? 0 : i.gpuInfo().gpus().stream()
                .mapToInt(g -> g.count() == null ? 0 : g.count()).sum();
        String gpuModel = i.gpuInfo() == null || i.gpuInfo().gpus().isEmpty() ? null : i.gpuInfo().gpus().get(0).name();
        int inf = i.inferenceAcceleratorInfo() == null ? 0 : i.inferenceAcceleratorInfo().accelerators().stream()
                .mapToInt(a -> a.count() == null ? 0 : a.count()).sum();
        String infModel = i.inferenceAcceleratorInfo() == null || i.inferenceAcceleratorInfo().accelerators().isEmpty()
                ? null : i.inferenceAcceleratorInfo().accelerators().get(0).name();
        int disks = i.instanceStorageInfo() == null ? 0 : i.instanceStorageInfo().disks().stream()
                .mapToInt(d -> d.count() == null ? 0 : d.count()).sum();
        var net = i.networkInfo();
        return new AwsTypeSelector.TypeFacts(i.instanceTypeAsString(),
                i.vCpuInfo() == null || i.vCpuInfo().defaultVCpus() == null ? 0 : i.vCpuInfo().defaultVCpus(),
                i.memoryInfo() == null || i.memoryInfo().sizeInMiB() == null ? 0 : i.memoryInfo().sizeInMiB(),
                new HashSet<>(i.processorInfo() == null ? List.of() : i.processorInfo().supportedArchitecturesAsStrings()),
                new HashSet<>(i.supportedUsageClassesAsStrings()),
                Boolean.TRUE.equals(i.burstablePerformanceSupported()),
                Boolean.TRUE.equals(i.bareMetal()),
                "nitro".equalsIgnoreCase(i.hypervisorAsString()),
                new HashSet<>(i.supportedBootModesAsStrings()),
                gpus + inf, gpuModel != null ? gpuModel : infModel, disks,
                net != null && Boolean.TRUE.equals(net.efaSupported()),
                net == null || net.maximumNetworkInterfaces() == null ? 0 : net.maximumNetworkInterfaces(),
                Boolean.TRUE.equals(i.hibernationSupported()),
                Boolean.TRUE.equals(i.currentGeneration()),
                Set.of(), 0);
    }

    private Map<String, Set<String>> offerings(AwsReadOnlyView view, Set<String> azs) {
        String key = view.region() + "|" + String.join(",", azs);
        Map<String, Set<String>> hit = offeringsCache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        Map<String, Set<String>> out = AwsErrors.read("DescribeInstanceTypeOfferings", () -> {
            Map<String, Set<String>> m = new HashMap<>();
            String token = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                DescribeInstanceTypeOfferingsResponse resp = view.ec2().describeInstanceTypeOfferings(
                        DescribeInstanceTypeOfferingsRequest.builder().locationType(LocationType.AVAILABILITY_ZONE)
                                .filters(Filter.builder().name("location").values(new ArrayList<>(azs)).build())
                                .nextToken(token).build());
                for (InstanceTypeOffering o : resp.instanceTypeOfferings()) {
                    m.computeIfAbsent(o.instanceTypeAsString(), t -> new HashSet<>()).add(o.location());
                }
                token = resp.nextToken();
                if (token == null) {
                    break;
                }
            }
            return m;
        });
        offeringsCache.put(key, out);
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
