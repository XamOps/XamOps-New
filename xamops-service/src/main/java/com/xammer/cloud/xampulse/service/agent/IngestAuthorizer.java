package com.xammer.cloud.xampulse.service.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Authorises one ingest request: the token must verify, and the agent it names must still be ACTIVE in its tenant.
 * Agent state is cached for 30 s, which is the longest a revoked agent can keep sending. A valid agent of a tenant
 * that is over its daily ingest quota gets {@link Status#QUOTA_EXCEEDED}.
 */
@Service("xpIngestAuthorizer")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class IngestAuthorizer {

    public enum Status { OK, UNAUTHORIZED, QUOTA_EXCEEDED }

    public record Decision(Status status, long tenantId, String instanceId, int retainDays, long retryAfterSec) {
        public Decision(Status status, long tenantId, String instanceId, int retainDays) {
            this(status, tenantId, instanceId, retainDays, 0);
        }

        static Decision denied() {
            return new Decision(Status.UNAUTHORIZED, 0, null, 0, 0);
        }
    }

    private record AgentView(boolean active, int retainDays) {}

    private final AgentTokenService tokens;
    private final XpAgentRepository agents;
    private final XpAccountConfigRepository configs;
    private final Cache<String, AgentView> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(50_000).build();

    private final IngestQuotaService quota;
    public IngestAuthorizer(AgentTokenService tokens, XpAgentRepository agents, XpAccountConfigRepository configs) {
        this(tokens, agents, configs, null);
    }
    @Autowired
    public IngestAuthorizer(AgentTokenService tokens, XpAgentRepository agents, XpAccountConfigRepository configs,
                            IngestQuotaService quota) {
        this.tokens = tokens;
        this.agents = agents;
        this.configs = configs;
        this.quota = quota;
    }

    public Decision authorize(String bearer) {
        String token = bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, 7) ? bearer.substring(7).trim() : bearer;
        Optional<AgentTokenService.AgentClaims> claims = tokens.verify(token);
        if (claims.isEmpty()) {
            return Decision.denied();
        }
        AgentTokenService.AgentClaims c = claims.get();
        AgentView v = cache.get(c.tenantId() + "|" + c.instanceId(), k -> load(c.tenantId(), c.instanceId()));
        if (v == null || !v.active()) {
            return Decision.denied();
        }
        // Quota is evaluated only after the token and the agent check pass, so unauthenticated callers learn nothing.
        if (quota != null) {
            java.util.OptionalLong retry = quota.retryAfterIfExceeded(c.tenantId());
            if (retry.isPresent()) {
                return new Decision(Status.QUOTA_EXCEEDED, c.tenantId(), c.instanceId(), v.retainDays(), retry.getAsLong());
            }
        }
        return new Decision(Status.OK, c.tenantId(), c.instanceId(), v.retainDays());
    }

    private AgentView load(long tenantId, String instanceId) {
        Optional<XpAgent> agent = agents.findByCloudAccountIdAndInstanceId(tenantId, instanceId);
        if (agent.isEmpty() || !"ACTIVE".equals(agent.get().getState())) {
            return new AgentView(false, 0);
        }
        int days = configs.findByCloudAccountId(tenantId).map(XpAccountConfig::getHotDays).orElse(14);
        return new AgentView(true, Math.max(3, Math.min(days, 30)));
    }
}
