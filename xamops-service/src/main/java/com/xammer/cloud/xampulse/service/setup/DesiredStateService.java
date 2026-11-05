package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The install document's authority (plan 3.7): {@code INSTALL} or {@code SKIP}, nothing else, no tenant data.
 * Anything unknown, disconnected, paused, revoked or not in scope answers SKIP, so the answer reveals only whether
 * this exact instance is covered. For tag and all-managed targeting the instance must be known to the inventory
 * plane and match the region's target; a brand-new instance answers SKIP until inventory has seen it, and the next
 * association run (30 minutes) asks again.
 */
@Service("xpDesiredStateService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class DesiredStateService {

    public static final String INSTALL = "INSTALL";
    public static final String SKIP = "SKIP";
    private static final Set<XpAccountState> ACTIVE = EnumSet.of(XpAccountState.PLANNED, XpAccountState.DEPLOYING,
            XpAccountState.VERIFYING, XpAccountState.CONNECTED, XpAccountState.DEGRADED);

    private final CloudAccountRepository accounts;
    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final InstanceCandidateService candidates;

    public DesiredStateService(CloudAccountRepository accounts, XpAccountConfigRepository configs,
                               XpAccountRegionRepository regions, XpAgentRepository agents, InstanceCandidateService candidates) {
        this.candidates = candidates;
        this.accounts = accounts;
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
    }

    public String answer(String awsAccountId, String instanceId) {
        if (awsAccountId == null || !awsAccountId.matches("^\\d{12}$") || instanceId == null
                || !SetupPlanValidator.INSTANCE_ID.matcher(instanceId).matches()) {
            return SKIP;
        }
        // The same AWS account can be connected more than once; the instance is covered if any connection covers it.
        for (CloudAccount a : accounts.findByAwsAccountId(awsAccountId)) {
            if (covers(a.getId(), instanceId)) {
                return INSTALL;
            }
        }
        return SKIP;
    }

    private boolean covers(Long cloudAccountId, String instanceId) {
        Optional<XpAccountConfig> cfg = configs.findByCloudAccountId(cloudAccountId);
        if (cfg.isEmpty() || !ACTIVE.contains(SetupStateMachine.parse(cfg.get().getState()))) {
            return false;
        }
        Optional<XpAgent> agent = agents.findByCloudAccountIdAndInstanceId(cloudAccountId, instanceId);
        if (agent.isPresent() && ("PAUSED".equals(agent.get().getState()) || "REVOKED".equals(agent.get().getState()))) {
            return false;
        }
        for (XpAccountRegion row : regions.findByCloudAccountId(cloudAccountId)) {
            switch (String.valueOf(row.getTargetMode())) {
                case "INSTANCES" -> {
                    if (InstanceCandidateService.targetIds(row).contains(instanceId)) {
                        return true;
                    }
                }
                default -> {
                    // Tag and all-managed: the instance must be known to inventory and match the row.
                    var found = candidates.inventoryLookup(cloudAccountId, instanceId);
                    if (found.isPresent() && row.getRegion().equals(found.get().seen().region())
                            && InstanceCandidateService.inScope(row, found.get().seen(), found.get().ssmManaged())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
