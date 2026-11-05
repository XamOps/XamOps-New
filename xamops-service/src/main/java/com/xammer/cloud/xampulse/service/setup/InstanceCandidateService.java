package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAgentInfo;
import com.xammer.cloud.xampulse.dto.XpInstanceCandidate;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InstanceInformation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * EC2 instances an account could observe, grouped by region. Reads the existing inventory plane (honouring
 * {@code isFresh}) and falls back to a live describe through the connection role when the plane is stale. Adds
 * the SSM-managed flag, whether the plan covers the instance, and the agent's last-seen state.
 */
@Service("xpInstanceCandidateService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class InstanceCandidateService {

    private static final Logger log = LoggerFactory.getLogger(InstanceCandidateService.class);
    private static final String EC2 = "EC2 Instance";
    private static final String SSM = "SSM Managed Instance";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** An agent that has not reported for this long is STALE. Heartbeats are every 30 s. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** Raw instance as seen by inventory or a live describe. */
    public record Seen(String instanceId, String name, String region, String state, Map<String, String> tags, String platform) {}

    public record Snapshot(List<Seen> instances, Set<String> ssmManaged, boolean live) {}

    private final InventoryProjectionService inventory;
    private final AwsClientProvider aws;
    private final XpAgentRepository agents;
    private final XpAccountRegionRepository regions;

    public InstanceCandidateService(InventoryProjectionService inventory, AwsClientProvider aws, XpAgentRepository agents,
                                    XpAccountRegionRepository regions) {
        this.inventory = inventory;
        this.aws = aws;
        this.agents = agents;
        this.regions = regions;
    }

    /** Inventory first; live describe only when the plane is not fresh. */
    /** One instance as the inventory plane knows it, for scope checks. Cached for a minute; no live fallback. */
    public record Lookup(Seen seen, boolean ssmManaged) {}

    private final com.github.benmanes.caffeine.cache.Cache<Long, Snapshot> inventoryCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(2000).build();

    /** Empty when the inventory plane does not know the instance (yet). */
    public java.util.Optional<Lookup> inventoryLookup(Long accountId, String instanceId) {
        Snapshot s = inventoryCache.get(accountId, id -> {
            List<Seen> seen = new ArrayList<>();
            for (ResourceDto r : inventory.byType(id, EC2)) {
                Map<String, String> d = r.getDetails() == null ? Map.of() : r.getDetails();
                seen.add(new Seen(r.getId(), r.getName() == null ? r.getId() : r.getName(), r.getRegion(), r.getState(), d, null));
            }
            Set<String> ssm = new HashSet<>();
            for (ResourceDto r : inventory.byType(id, SSM)) {
                ssm.add(r.getId());
            }
            return new Snapshot(seen, ssm, false);
        });
        return s.instances().stream().filter(i -> i.instanceId().equals(instanceId)).findFirst()
                .map(i -> new Lookup(i, s.ssmManaged().contains(instanceId)));
    }

    public Snapshot snapshot(CloudAccount account) {
        Long id = account.getId();
        if (inventory.isFresh(id)) {
            List<Seen> seen = new ArrayList<>();
            for (ResourceDto r : inventory.byType(id, EC2)) {
                Map<String, String> d = r.getDetails() == null ? Map.of() : r.getDetails();
                seen.add(new Seen(r.getId(), r.getName() == null ? r.getId() : r.getName(), r.getRegion(), r.getState(), d, null));
            }
            Set<String> ssm = new HashSet<>();
            for (ResourceDto r : inventory.byType(id, SSM)) {
                ssm.add(r.getId());
            }
            return new Snapshot(seen, ssm, false);
        }
        return live(account);
    }

    private Snapshot live(CloudAccount account) {
        List<Seen> seen = new ArrayList<>();
        Set<String> ssm = new HashSet<>();
        List<String> regionNames = new ArrayList<>();
        try {
            Ec2Client ec2 = aws.getEc2Client(account, "us-east-1");
            ec2.describeRegions().regions().forEach(r -> regionNames.add(r.regionName()));
        } catch (RuntimeException e) {
            log.warn("XamPulse live instance listing: could not list regions for account {}: {}", account.getId(), e.toString());
        }
        for (String region : regionNames) {
            try {
                Ec2Client ec2 = aws.getEc2Client(account, region);
                for (Reservation res : ec2.describeInstancesPaginator().reservations()) {
                    for (Instance i : res.instances()) {
                        Map<String, String> tags = new HashMap<>();
                        i.tags().forEach(t -> tags.put(t.key(), t.value()));
                        seen.add(new Seen(i.instanceId(), tags.getOrDefault("Name", i.instanceId()), region,
                                i.state() == null ? "unknown" : i.state().nameAsString(), tags,
                                i.platformAsString() != null && i.platformAsString().equalsIgnoreCase("windows") ? "WINDOWS" : "LINUX"));
                    }
                }
                SsmClient s = aws.getSsmClient(account, region);
                for (InstanceInformation info : s.describeInstanceInformationPaginator().instanceInformationList()) {
                    ssm.add(info.instanceId());
                }
            } catch (RuntimeException e) {
                // A region that is disabled or denied must not hide the others.
                log.debug("XamPulse live instance listing skipped region {} for account {}: {}", region, account.getId(), e.toString());
            }
        }
        return new Snapshot(seen, ssm, true);
    }

    public List<String> activeRegions(Snapshot s) {
        TreeSet<String> out = new TreeSet<>();
        s.instances().forEach(i -> {
            if (i.region() != null) {
                out.add(i.region());
            }
        });
        return List.copyOf(out);
    }

    /** All candidates for an account with scope and agent state, sorted by region then name. */
    public List<XpInstanceCandidate> candidates(CloudAccount account) {
        Snapshot s = snapshot(account);
        Map<String, XpAgent> agentById = new HashMap<>();
        agents.findByCloudAccountId(account.getId()).forEach(a -> agentById.put(a.getInstanceId(), a));
        Map<String, XpAccountRegion> regionRows = new HashMap<>();
        regions.findByCloudAccountId(account.getId()).forEach(r -> regionRows.put(r.getRegion(), r));
        List<XpInstanceCandidate> out = new ArrayList<>();
        for (Seen i : s.instances()) {
            XpAgent a = agentById.get(i.instanceId());
            boolean managed = s.ssmManaged().contains(i.instanceId());
            out.add(new XpInstanceCandidate(i.instanceId(), i.name(), i.region(), i.platform(), i.state(), i.tags(),
                    managed, inScope(regionRows.get(i.region()), i, managed), a == null || a.getCollectionLevel() == null ? "T1" : a.getCollectionLevel(),
                    agentInfo(a, Instant.now())));
        }
        out.sort(Comparator.comparing(XpInstanceCandidate::region, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(XpInstanceCandidate::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return out;
    }

    /** Whether the plan on this region row covers the instance. */
    public static boolean inScope(XpAccountRegion row, Seen i, boolean ssmManaged) {
        if (row == null) {
            return false;
        }
        switch (String.valueOf(row.getTargetMode())) {
            case "ALL_MANAGED":
                return ssmManaged;
            case "TAG":
                return row.getObserveTagKey() != null && i.tags() != null
                        && i.tags().containsKey(row.getObserveTagKey())
                        && (row.getObserveTagValue() == null || row.getObserveTagValue().isEmpty()
                        || row.getObserveTagValue().equals(i.tags().get(row.getObserveTagKey())));
            default:
                return targetIds(row).contains(i.instanceId());
        }
    }

    public static Set<String> targetIds(XpAccountRegion row) {
        String json = row.getTargetInstanceIdsJson();
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> ids = JSON.readValue(json, new TypeReference<List<String>>() {});
            return new HashSet<>(ids);
        } catch (Exception e) {
            return Set.of();
        }
    }

    public static XpAgentInfo agentInfo(XpAgent a, Instant now) {
        if (a == null) {
            return new XpAgentInfo(null, null, "NONE");
        }
        String status;
        if ("REVOKED".equals(a.getState())) {
            status = "REVOKED";
        } else if ("PAUSED".equals(a.getState())) {
            status = "NONE"; // collection switched off by an admin; the contract has no PAUSED value
        } else if (a.getLastSeenAt() == null) {
            status = "PENDING";
        } else if (Duration.between(a.getLastSeenAt(), now).compareTo(STALE_AFTER) > 0) {
            status = "STALE";
        } else {
            status = "ACTIVE";
        }
        return new XpAgentInfo(a.getAgentVersion(), a.getLastSeenAt(), status);
    }
}
