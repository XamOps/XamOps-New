package com.xammer.cloud.xampulse.service.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.setup.InstanceCandidateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingInstanceDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free AWS facts about one EC2 instance (DescribeInstances, InstanceTypes, InstanceStatus, Volumes, Images,
 * InstanceCreditSpecifications, Auto Scaling instances) under the customer's connection role. No agent and no
 * CloudWatch call. Cached for {@link #TTL} per instance so opening the page repeatedly does not hammer EC2.
 * Tenancy: the EC2 client is the customer's own role, so an instance from another account is simply not found.
 */
@Service("xpEc2FactsService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class Ec2FactsService {

    private static final Logger log = LoggerFactory.getLogger(Ec2FactsService.class);
    static final Duration TTL = Duration.ofSeconds(60);
    static final long OLD_AMI_DAYS = 365;

    private final AwsClientProvider aws;
    private final XpAgentRepository agents;
    private final XpAccountConfigRepository configs;
    private final InstanceCandidateService candidates;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(XpEc2Facts facts, Instant at) {}

    @org.springframework.beans.factory.annotation.Autowired
    public Ec2FactsService(AwsClientProvider aws, XpAgentRepository agents, XpAccountConfigRepository configs,
                           InstanceCandidateService candidates) {
        this(aws, agents, configs, candidates, Clock.systemUTC());
    }

    Ec2FactsService(AwsClientProvider aws, XpAgentRepository agents, XpAccountConfigRepository configs,
                    InstanceCandidateService candidates, Clock clock) {
        this.aws = aws;
        this.agents = agents;
        this.configs = configs;
        this.candidates = candidates;
        this.clock = clock;
    }

    /** Region of an instance: the explicit hint, the agent row, the inventory, then the account's home region. */
    public String regionOf(CloudAccount account, String instanceId, String hint) {
        if (hint != null && hint.matches("^[a-z]{2}(-[a-z]+)+-\\d$")) {
            return hint;
        }
        Optional<String> fromAgent = agents.findByCloudAccountIdAndInstanceId(account.getId(), instanceId)
                .map(a -> a.getRegion()).filter(r -> r != null && !r.isBlank());
        if (fromAgent.isPresent()) {
            return fromAgent.get();
        }
        try {
            Optional<String> fromInventory = candidates.inventoryLookup(account.getId(), instanceId).map(l -> l.seen().region());
            if (fromInventory.isPresent() && fromInventory.get() != null) {
                return fromInventory.get();
            }
        } catch (RuntimeException e) {
            log.debug("XamPulse inventory lookup failed: {}", e.getClass().getSimpleName());
        }
        return configs.findByCloudAccountId(account.getId()).map(c -> c.getHomeRegion())
                .filter(r -> r != null && !r.isBlank()).orElseThrow(XamPulseException::notFound);
    }

    public XpEc2Facts facts(CloudAccount account, String instanceId, String regionHint) {
        String region = regionOf(account, instanceId, regionHint);
        String key = account.getId() + "|" + region + "|" + instanceId;
        Cached c = cache.get(key);
        Instant now = clock.instant();
        if (c != null && c.at().plus(TTL).isAfter(now)) {
            return c.facts();
        }
        XpEc2Facts f = load(account, region, instanceId, now);
        cache.put(key, new Cached(f, now));
        if (cache.size() > 5000) {
            cache.entrySet().removeIf(e -> e.getValue().at().plus(TTL).isBefore(now));
        }
        return f;
    }

    /** What peers are matched on: instance type, Auto Scaling group (from its tag) and the Name tag. */
    public record PeerKey(String instanceId, String instanceType, String autoScalingGroup, String name,
                          String state, String availabilityZone, Map<String, String> tags) {
        /** Peer matching only needs these four; state, zone and tags are for the fleet list. */
        public PeerKey(String instanceId, String instanceType, String autoScalingGroup, String name) {
            this(instanceId, instanceType, autoScalingGroup, name, null, null, Map.of());
        }
    }

    /** Customer tags for display and grouping; AWS-managed keys (aws:...) are noise there and are left out. */
    static Map<String, String> userTags(List<Tag> tags) {
        Map<String, String> out = new TreeMap<>();
        for (Tag t : tags) {
            if (t.key() != null && !t.key().startsWith("aws:")) {
                out.put(t.key(), t.value() == null ? "" : t.value());
            }
        }
        return out;
    }

    static final Duration PEER_TTL = Duration.ofMinutes(10);
    private final Map<String, CachedKeys> peerCache = new ConcurrentHashMap<>();

    private record CachedKeys(Map<String, PeerKey> keys, Set<String> requestedIds, Instant at) {}

    /**
     * Peer keys for many instances in one region: one paginated DescribeInstances (an EC2 API call, never CloudWatch,
     * not billed), cached 10 minutes per account and region. Instances that no longer exist are simply absent. Empty
     * when EC2 cannot be read (missing permission, throttling): peers are then "not available", never guessed.
     */
    public Map<String, PeerKey> peerKeys(CloudAccount account, String region, Collection<String> instanceIds) {
        String key = account.getId() + "|" + region;
        Instant now = clock.instant();
        Set<String> requestedIds = new LinkedHashSet<>(instanceIds);
        CachedKeys c = peerCache.get(key);
        if (c != null && c.at().plus(PEER_TTL).isAfter(now) && c.requestedIds().containsAll(requestedIds)) {
            return c.keys();
        }
        Map<String, PeerKey> out = new HashMap<>();
        List<String> ids = new ArrayList<>(requestedIds);
        try {
            Ec2Client ec2 = aws.getEc2Client(account, region);
            for (int i = 0; i < ids.size(); i += 200) {
                List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 200));
                // A filter (unlike instanceIds) does not fail the whole call when one instance is gone.
                ec2.describeInstancesPaginator(r -> r.filters(Filter.builder().name("instance-id").values(chunk).build()))
                        .reservations().forEach(res -> res.instances().forEach(inst -> {
                            String asg = null;
                            String name = null;
                            for (Tag t : inst.tags()) {
                                if ("aws:autoscaling:groupName".equals(t.key())) {
                                    asg = t.value();
                                } else if ("Name".equals(t.key())) {
                                    name = t.value();
                                }
                            }
                            out.put(inst.instanceId(), new PeerKey(inst.instanceId(), inst.instanceTypeAsString(), asg, name,
                                    inst.state() == null ? null : inst.state().nameAsString(),
                                    inst.placement() == null ? null : inst.placement().availabilityZone(),
                                    userTags(inst.tags())));
                        }));
            }
        } catch (SdkException e) {
            log.warn("XamPulse peer lookup failed: {}", e.getClass().getSimpleName());
            return Map.of();
        }
        peerCache.put(key, new CachedKeys(Map.copyOf(out), Set.copyOf(requestedIds), now));
        if (peerCache.size() > 2000) {
            peerCache.entrySet().removeIf(e -> e.getValue().at().plus(PEER_TTL).isBefore(now));
        }
        return out;
    }

    /**
     * One inbound rule of a security group. {@code protocol}: tcp, udp, icmp... or "all"; ports null for all ports.
     * {@code sources}: CIDRs (IPv4 and IPv6), prefix list ids (pl-...) and security group ids (sg-...).
     */
    public record IngressRule(String groupId, String groupName, String protocol, Integer fromPort, Integer toPort,
                              List<String> sources, String description) {}

    private final Map<String, CachedRules> ruleCache = new ConcurrentHashMap<>();

    private record CachedRules(List<IngressRule> rules, Instant at) {}

    /**
     * Inbound rules of the given security groups (DescribeSecurityGroups: an EC2 API call, not billed), cached like
     * the facts. Throws {@link XamPulseException} (unavailable) when EC2 cannot be read: an unread rule set must never
     * be shown as "nothing is open".
     */
    public List<IngressRule> ingress(CloudAccount account, String region, List<String> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        String key = account.getId() + "|" + region + "|" + String.join(",", new TreeSet<>(groupIds));
        Instant now = clock.instant();
        CachedRules c = ruleCache.get(key);
        if (c != null && c.at().plus(TTL).isAfter(now)) {
            return c.rules();
        }
        List<IngressRule> out = new ArrayList<>();
        try {
            for (SecurityGroup g : aws.getEc2Client(account, region).describeSecurityGroups(r -> r.groupIds(groupIds)).securityGroups()) {
                for (IpPermission p : g.ipPermissions()) {
                    String proto = switch (p.ipProtocol() == null ? "-1" : p.ipProtocol()) {
                        case "-1" -> "all";
                        case "6" -> "tcp";
                        case "17" -> "udp";
                        default -> p.ipProtocol();
                    };
                    List<String> sources = new ArrayList<>();
                    p.ipRanges().forEach(r -> sources.add(r.cidrIp()));
                    p.ipv6Ranges().forEach(r -> sources.add(r.cidrIpv6()));
                    p.prefixListIds().forEach(r -> sources.add(r.prefixListId()));
                    p.userIdGroupPairs().forEach(r -> sources.add(r.groupId()));
                    boolean allPorts = "all".equals(proto) || p.fromPort() == null || p.fromPort() == -1;
                    out.add(new IngressRule(g.groupId(), g.groupName(), proto, allPorts ? null : p.fromPort(), allPorts ? null : p.toPort(),
                            List.copyOf(sources), p.ipRanges().stream().map(IpRange::description).filter(Objects::nonNull).findFirst().orElse(null)));
                }
            }
        } catch (SdkException e) {
            throw unavailable(e);
        }
        ruleCache.put(key, new CachedRules(List.copyOf(out), now));
        if (ruleCache.size() > 2000) {
            ruleCache.entrySet().removeIf(e -> e.getValue().at().plus(TTL).isBefore(now));
        }
        return out;
    }

    private XpEc2Facts load(CloudAccount account, String region, String instanceId, Instant now) {
        Ec2Client ec2 = aws.getEc2Client(account, region);
        Instance i;
        try {
            List<Reservation> res = ec2.describeInstances(r -> r.instanceIds(instanceId)).reservations();
            i = res.stream().flatMap(r -> r.instances().stream()).findFirst().orElseThrow(XamPulseException::notFound);
        } catch (Ec2Exception e) {
            if (e.awsErrorDetails() != null && String.valueOf(e.awsErrorDetails().errorCode()).startsWith("InvalidInstanceID")) {
                throw XamPulseException.notFound();
            }
            throw unavailable(e);
        } catch (SdkException e) {
            throw unavailable(e);
        }

        Map<String, String> tags = new LinkedHashMap<>();
        i.tags().forEach(t -> tags.put(t.key(), t.value()));
        String type = i.instanceTypeAsString();

        // Each extra call is best effort: a missing permission or a deregistered AMI must not hide the rest.
        InstanceTypeInfo typeInfo = quiet(() -> ec2.describeInstanceTypes(r -> r.instanceTypesWithStrings(type))
                .instanceTypes().stream().findFirst().orElse(null));
        boolean burstable = typeInfo != null && Boolean.TRUE.equals(typeInfo.burstablePerformanceSupported());
        String credits = burstable ? quiet(() -> ec2.describeInstanceCreditSpecifications(r -> r.instanceIds(instanceId))
                .instanceCreditSpecifications().stream().findFirst().map(InstanceCreditSpecification::cpuCredits).orElse(null)) : null;
        InstanceStatus status = quiet(() -> ec2.describeInstanceStatus(r -> r.instanceIds(instanceId).includeAllInstances(true))
                .instanceStatuses().stream().findFirst().orElse(null));
        Image image = i.imageId() == null ? null : quiet(() -> ec2.describeImages(r -> r.imageIds(i.imageId())).images().stream().findFirst().orElse(null));

        Map<String, InstanceBlockDeviceMapping> byVolume = new LinkedHashMap<>();
        i.blockDeviceMappings().forEach(b -> {
            if (b.ebs() != null && b.ebs().volumeId() != null) {
                byVolume.put(b.ebs().volumeId(), b);
            }
        });
        List<XpEc2Facts.Volume> volumes = new ArrayList<>();
        if (!byVolume.isEmpty()) {
            List<Volume> vs = quiet(() -> ec2.describeVolumes(r -> r.volumeIds(byVolume.keySet())).volumes());
            if (vs != null) {
                for (Volume v : vs) {
                    InstanceBlockDeviceMapping m = byVolume.get(v.volumeId());
                    volumes.add(new XpEc2Facts.Volume(v.volumeId(), m == null ? null : m.deviceName(), v.volumeTypeAsString(),
                            v.size() == null ? 0 : v.size(), v.iops(), v.throughput(), Boolean.TRUE.equals(v.encrypted()),
                            m != null && m.ebs() != null && Boolean.TRUE.equals(m.ebs().deleteOnTermination())));
                }
            }
        }

        AutoScalingInstanceDetails asg = quiet(() -> {
            AutoScalingClient as = aws.getAutoScalingClient(account, region);
            return as.describeAutoScalingInstances(r -> r.instanceIds(instanceId)).autoScalingInstances().stream().findFirst().orElse(null);
        });

        List<XpEc2Facts.ScheduledEvent> events = new ArrayList<>();
        if (status != null) {
            for (InstanceStatusEvent e : status.events()) {
                // AWS keeps finished events around for a while, prefixing the description with [Completed] or
                // [Canceled]; neither needs action, so neither may show up as an upcoming event.
                if (isFinishedEvent(e.description())) {
                    continue;
                }
                events.add(new XpEc2Facts.ScheduledEvent(e.codeAsString(), e.description(), e.notBefore(), e.notAfter()));
            }
        }
        XpEc2Facts.StatusChecks checks = new XpEc2Facts.StatusChecks(
                status == null || status.systemStatus() == null ? "insufficient-data" : status.systemStatus().statusAsString(),
                status == null || status.instanceStatus() == null ? "insufficient-data" : status.instanceStatus().statusAsString());

        Instant amiCreated = image == null || image.creationDate() == null ? null : parseInstant(image.creationDate());
        String lifecycle = i.instanceLifecycleAsString() == null ? "on-demand" : i.instanceLifecycleAsString();
        boolean imdsV2 = i.metadataOptions() != null && "required".equals(i.metadataOptions().httpTokensAsString());
        boolean detailed = i.monitoring() != null && "enabled".equals(i.monitoring().stateAsString());
        List<String> sgs = i.securityGroups().stream().map(g -> g.groupName() + " (" + g.groupId() + ")").toList();

        XpEc2Facts draft = new XpEc2Facts(instanceId, region, tags.getOrDefault("Name", instanceId),
                i.state() == null ? null : i.state().nameAsString(), type,
                typeInfo == null || typeInfo.vCpuInfo() == null ? null : typeInfo.vCpuInfo().defaultVCpus(),
                typeInfo == null || typeInfo.memoryInfo() == null ? null : typeInfo.memoryInfo().sizeInMiB(),
                i.architectureAsString(), i.platformDetails(), burstable, credits,
                typeInfo == null || typeInfo.networkInfo() == null ? null : typeInfo.networkInfo().networkPerformance(),
                ebsBaselineIops(typeInfo), ebsBaselineMbps(typeInfo),
                i.placement() == null ? null : i.placement().availabilityZone(), i.vpcId(), i.subnetId(), i.privateIpAddress(),
                i.publicIpAddress(), i.imageId(), image == null ? null : image.name(), amiCreated, i.launchTime(), lifecycle,
                asg == null ? null : asg.autoScalingGroupName(), asg == null ? null : asg.lifecycleState(),
                i.iamInstanceProfile() == null ? null : i.iamInstanceProfile().arn(), sgs, imdsV2,
                Boolean.TRUE.equals(i.ebsOptimized()), detailed, tags, volumes, checks, events, List.of(), now);
        return withFindings(draft, now);
    }

    /** Plain-language observations. Pure, so the rules are unit-tested without AWS. */
    static XpEc2Facts withFindings(XpEc2Facts f, Instant now) {
        List<XpEc2Facts.Finding> out = new ArrayList<>();
        if ("impaired".equals(f.status().system())) {
            out.add(new XpEc2Facts.Finding("CRITICAL", "AWS system status check is failing",
                    "The problem is on the AWS host (power, network, hardware). Stopping and starting the instance moves it to healthy hardware; a reboot does not."));
        }
        if ("impaired".equals(f.status().instance())) {
            out.add(new XpEc2Facts.Finding("CRITICAL", "Instance status check is failing",
                    "The OS is not responding to AWS probes (kernel panic, exhausted memory, broken network config, full root disk). Check the system log / screenshot in the EC2 console."));
        }
        for (XpEc2Facts.ScheduledEvent e : f.scheduledEvents()) {
            String when = e.notBefore() == null ? "soon" : "from " + e.notBefore();
            out.add(new XpEc2Facts.Finding("WARNING", "Scheduled " + humanEvent(e.code()) + " " + when,
                    (e.description() == null ? "" : e.description() + ". ")
                            + ("instance-retirement".equals(e.code()) || "instance-stop".equals(e.code())
                            ? "Stop and start the instance before then to move it off the retiring hardware."
                            : "Plan a window; the instance restarts during the event.")));
        }
        if (f.burstable()) {
            out.add("unlimited".equals(f.cpuCredits())
                    ? new XpEc2Facts.Finding("INFO", "Burstable instance in unlimited mode",
                    "It never throttles, but sustained CPU above baseline is billed as surplus credits. Refresh CloudWatch data to see the credit balance.")
                    : new XpEc2Facts.Finding("INFO", "Burstable instance in standard mode",
                    "When the CPU credit balance reaches zero the instance is throttled to its baseline. Refresh CloudWatch data to see the balance."));
        }
        if (!f.imdsV2Required() && "running".equals(f.state())) {
            out.add(new XpEc2Facts.Finding("WARNING", "IMDSv1 is allowed",
                    "Instance metadata can be read without a session token, which SSRF bugs can exploit to steal the instance role's credentials. Set HttpTokens=required."));
        }
        if (f.iamInstanceProfile() == null) {
            out.add(new XpEc2Facts.Finding("INFO", "No IAM instance profile",
                    "Systems Manager and the XamPulse agent need an instance role (AmazonSSMManagedInstanceCore)."));
        }
        long gp2 = f.volumes().stream().filter(v -> "gp2".equals(v.type())).count();
        if (gp2 > 0) {
            out.add(new XpEc2Facts.Finding("INFO", gp2 + " gp2 volume" + (gp2 == 1 ? "" : "s"),
                    "gp3 costs about 20% less per GiB and gives 3,000 IOPS / 125 MiB/s regardless of size; gp2 performance depends on size and burst credits."));
        }
        long unencrypted = f.volumes().stream().filter(v -> !v.encrypted()).count();
        if (unencrypted > 0) {
            out.add(new XpEc2Facts.Finding("WARNING", unencrypted + " unencrypted EBS volume" + (unencrypted == 1 ? "" : "s"),
                    "Data at rest and snapshots of these volumes are not encrypted."));
        }
        if (f.amiCreatedAt() != null && Duration.between(f.amiCreatedAt(), now).toDays() > OLD_AMI_DAYS) {
            out.add(new XpEc2Facts.Finding("WARNING", "AMI is " + Duration.between(f.amiCreatedAt(), now).toDays() + " days old",
                    "Images this old usually miss kernel and package security fixes unless the instance is patched in place."));
        }
        if ("spot".equals(f.lifecycle())) {
            out.add(new XpEc2Facts.Finding("INFO", "Spot instance",
                    "AWS can reclaim it with a two-minute notice; keep state off the instance."));
        }
        if (f.autoScalingLifecycleState() != null && !"InService".equals(f.autoScalingLifecycleState())) {
            out.add(new XpEc2Facts.Finding("WARNING", "Auto Scaling state is " + f.autoScalingLifecycleState(),
                    "The group " + f.autoScalingGroup() + " is not counting this instance as in service."));
        }
        if (f.publicIp() != null) {
            out.add(new XpEc2Facts.Finding("INFO", "Public IPv4 address " + f.publicIp(),
                    "Reachable from the internet subject to its security groups; public IPv4 addresses are billed hourly."));
        }
        if (!f.detailedMonitoring()) {
            out.add(new XpEc2Facts.Finding("INFO", "Basic CloudWatch monitoring",
                    "AWS records EC2 metrics every 5 minutes for this instance; the XamPulse agent adds 30-second OS data."));
        }
        out.sort(Comparator.comparingInt(x -> switch (x.severity()) { case "CRITICAL" -> 0; case "WARNING" -> 1; default -> 2; }));
        return new XpEc2Facts(f.instanceId(), f.region(), f.name(), f.state(), f.instanceType(), f.vcpus(), f.memoryMiB(),
                f.architecture(), f.platform(), f.burstable(), f.cpuCredits(), f.networkPerformance(), f.ebsBaselineIops(),
                f.ebsBaselineMbps(), f.availabilityZone(), f.vpcId(), f.subnetId(), f.privateIp(), f.publicIp(), f.amiId(),
                f.amiName(), f.amiCreatedAt(), f.launchTime(), f.lifecycle(), f.autoScalingGroup(), f.autoScalingLifecycleState(),
                f.iamInstanceProfile(), f.securityGroups(), f.imdsV2Required(), f.ebsOptimized(), f.detailedMonitoring(), f.tags(),
                f.volumes(), f.status(), f.scheduledEvents(), List.copyOf(out), f.fetchedAt());
    }

    static boolean isFinishedEvent(String description) {
        return description != null && (description.startsWith("[Completed]") || description.startsWith("[Canceled]")
                || description.startsWith("[Cancelled]"));
    }

    private static String humanEvent(String code) {
        return code == null ? "event" : switch (code) {
            case "instance-reboot" -> "instance reboot";
            case "system-reboot" -> "host reboot";
            case "system-maintenance" -> "host maintenance";
            case "instance-retirement" -> "instance retirement";
            case "instance-stop" -> "instance stop";
            default -> code;
        };
    }

    private static Integer ebsBaselineIops(InstanceTypeInfo t) {
        return t == null || t.ebsInfo() == null || t.ebsInfo().ebsOptimizedInfo() == null ? null : t.ebsInfo().ebsOptimizedInfo().baselineIops();
    }

    private static Double ebsBaselineMbps(InstanceTypeInfo t) {
        return t == null || t.ebsInfo() == null || t.ebsInfo().ebsOptimizedInfo() == null ? null
                : t.ebsInfo().ebsOptimizedInfo().baselineThroughputInMBps();
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T quiet(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.debug("XamPulse optional EC2 call skipped: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static XamPulseException unavailable(Exception e) {
        return new XamPulseException("XP_AWS_UNAVAILABLE", 502, "Could not read the instance from AWS. Check the account connection.");
    }
}
