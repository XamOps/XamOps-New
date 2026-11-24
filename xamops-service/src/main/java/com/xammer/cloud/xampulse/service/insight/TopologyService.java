package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.alert.IncidentCorrelator;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A host's tier for incident grouping: its Auto Scaling group, else its instance type and Name pattern (the same
 * rules as peer comparison). Read from the cached EC2 lookup ({@link Ec2FactsService#peerKeys}); any failure gives
 * no tier, and incidents then group by instance and rule only, as before.
 */
@Service("xpTopologyService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class TopologyService {

    private static final Logger log = LoggerFactory.getLogger(TopologyService.class);

    private final CloudAccountRepository accounts;
    private final XpAgentRepository agents;
    private final Ec2FactsService ec2;

    public TopologyService(CloudAccountRepository accounts, XpAgentRepository agents, Ec2FactsService ec2) {
        this.accounts = accounts;
        this.agents = agents;
        this.ec2 = ec2;
    }

    /**
     * EC2 identity (Name tag, type, Auto Scaling group) for the account's instances, from the cached EC2 lookup.
     * Empty for a non-AWS account or when EC2 cannot be read: callers fall back to the instance id.
     */
    public Map<String, Ec2FactsService.PeerKey> keys(Long accountId, Collection<String> instanceIds) {
        try {
            CloudAccount account = accounts.findById(accountId).orElse(null);
            if (account == null || account.getAwsAccountId() == null || instanceIds.isEmpty()) {
                return Map.of();
            }
            Set<String> wanted = Set.copyOf(instanceIds);
            Map<String, List<String>> byRegion = new HashMap<>();
            for (XpAgent a : agents.findByCloudAccountId(accountId)) {
                if (wanted.contains(a.getInstanceId()) && a.getRegion() != null) {
                    byRegion.computeIfAbsent(a.getRegion(), k -> new ArrayList<>()).add(a.getInstanceId());
                }
            }
            Map<String, Ec2FactsService.PeerKey> out = new HashMap<>();
            byRegion.forEach((region, ids) -> ec2.peerKeys(account, region, ids).forEach((id, k) -> {
                if (wanted.contains(id)) {
                    out.put(id, k);
                }
            }));
            return out;
        } catch (RuntimeException e) {
            log.warn("XamPulse EC2 identity lookup failed: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    public Map<String, IncidentCorrelator.Tier> tiers(Long accountId, Collection<String> instanceIds) {
        Map<String, IncidentCorrelator.Tier> out = new HashMap<>();
        keys(accountId, instanceIds).forEach((id, k) -> {
            IncidentCorrelator.Tier t = tier(k);
            if (t != null) {
                out.put(id, t);
            }
        });
        return out;
    }

    static IncidentCorrelator.Tier tier(Ec2FactsService.PeerKey k) {
        if (k.autoScalingGroup() != null) {
            return new IncidentCorrelator.Tier("asg:" + k.autoScalingGroup(), "Auto Scaling group " + k.autoScalingGroup());
        }
        String p = PeerOutlierService.namePattern(k.name());
        if (k.instanceType() != null && p != null) {
            return new IncidentCorrelator.Tier("tn:" + k.instanceType() + ":" + p, k.instanceType() + " named " + p + "-*");
        }
        return null;
    }
}
