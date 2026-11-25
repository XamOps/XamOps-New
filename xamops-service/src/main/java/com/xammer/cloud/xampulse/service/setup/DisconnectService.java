package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.dto.XpDisconnectRequest;
import com.xammer.cloud.xampulse.dto.XpDisconnectResponse;
import com.xammer.cloud.xampulse.dto.XpStackDeletionLink;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Disconnect: every agent is revoked and told to uninstall on its next heartbeat, the desired state flips to SKIP
 * so the 30-minute association cannot bring the agent back, and the customer gets links to delete their own stacks.
 * Nothing is deleted in the customer's account by XamOps, and the archive stays in their bucket. With the archive feature
 * on, the caller may also ask for the hot store to be purged.
 */
@Service("xpDisconnectService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class DisconnectService {

    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final XpAgentCredentialRepository credentials;
    private final AuditService audit;
    private final com.xammer.cloud.xampulse.service.archive.TenantPurgeService purge;
    private final com.xammer.cloud.xampulse.config.XamPulseProperties props;

    public DisconnectService(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                             XpAgentCredentialRepository credentials, AuditService audit,
                             com.xammer.cloud.xampulse.service.archive.TenantPurgeService purge,
                             com.xammer.cloud.xampulse.config.XamPulseProperties props) {
        this.purge = purge;
        this.props = props;
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
        this.credentials = credentials;
        this.audit = audit;
    }

    @Transactional
    public XpDisconnectResponse disconnect(CloudAccount account, XpDisconnectRequest req, ClientUserDetails user, String ip) {
        if (req == null || !"DISCONNECT".equals(req.confirm())) {
            throw XamPulseException.invalid("Type DISCONNECT to confirm.");
        }
        if (req.purgeHotData() && !props.getArchive().isEnabled()) {
            // Purge ships together with the archive writer; refuse instead of pretending data was removed.
            throw XamPulseException.invalid("Purging hot data is not available yet. Disconnect without purge, then ask for deletion.");
        }
        XpAccountConfig cfg = configs.findByCloudAccountId(account.getId()).orElseThrow(XamPulseException::notSetUp);
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        if (state == XpAccountState.NOT_SET_UP) {
            throw XamPulseException.notSetUp();
        }
        // Idempotent: a second call re-applies the same end state and returns the same links.
        Instant now = Instant.now();
        for (XpAgent a : agents.findByCloudAccountId(account.getId())) {
            a.setState("REVOKED");
            a.setPendingCommand("UNINSTALL");
            agents.save(a);
            credentials.findByAgentId(a.getId()).forEach(c -> {
                if (c.getRevokedAt() == null) {
                    c.setRevokedAt(now);
                    credentials.save(c);
                }
            });
        }
        if (state != XpAccountState.DISCONNECTED) {
            cfg.setState(SetupStateMachine.move(state, XpAccountState.DISCONNECTED).name());
            cfg.setUpdatedAt(now);
            configs.save(cfg);
        }
        if (req.purgeHotData()) {
            // Hot store only; the archive in the customer's bucket is theirs and stays. Failures surface, nothing is faked.
            purge.purge(account.getId());
        }
        List<XpStackDeletionLink> links = new ArrayList<>();
        for (XpAccountRegion r : regions.findByCloudAccountId(account.getId())) {
            if (r.getStackName() != null) {
                links.add(new XpStackDeletionLink(r.getRegion(), XamPulseTemplateService.stacksConsoleUrl(r.getRegion(), r.getStackName())));
            }
        }
        audit.record(user, account.getId(), "setup.disconnect", req.purgeHotData() ? "agents revoked, hot data purge submitted" : "agents revoked", ip);
        return new XpDisconnectResponse(UUID.randomUUID().toString(), links);
    }
}
