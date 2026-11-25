package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpAgentCredential;
import com.xammer.cloud.xampulse.dto.agent.AgentConfig;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatRequest;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatResponse;
import com.xammer.cloud.xampulse.dto.agent.TokenPair;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What an enrolled agent does with its credentials: rotate the refresh token, heartbeat, read its configuration.
 * Refresh tokens rotate on every use and are stored only as SHA-256. Presenting an already-used refresh token means it
 * was copied, so the whole family is revoked; the agent then re-proves its identity and gets a new family, or is
 * told it is no longer covered.
 */
@Service("xpAgentSessionService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionService.class);

    public enum RefreshStatus { OK, DENIED }

    public record Refreshed(RefreshStatus status, TokenPair pair) {}

    private final XpAgentRepository agents;
    private final XpAgentCredentialRepository credentials;
    private final AgentTokenService tokens;
    private final AgentConfigService configService;
    private final XamPulseProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public AgentSessionService(XpAgentRepository agents, XpAgentCredentialRepository credentials, AgentTokenService tokens,
                               AgentConfigService configService, XamPulseProperties props) {
        this(agents, credentials, tokens, configService, props, Clock.systemUTC());
    }

    AgentSessionService(XpAgentRepository agents, XpAgentCredentialRepository credentials, AgentTokenService tokens,
                        AgentConfigService configService, XamPulseProperties props, Clock clock) {
        this.agents = agents;
        this.credentials = credentials;
        this.tokens = tokens;
        this.configService = configService;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Refreshed refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.length() < 20 || refreshToken.length() > 200) {
            return denied();
        }
        Optional<XpAgentCredential> found = credentials.findByRefreshHash(AgentEnrollmentService.sha256Hex(refreshToken));
        if (found.isEmpty()) {
            return denied();
        }
        XpAgentCredential cred = found.get();
        Instant now = clock.instant();
        if (cred.getRevokedAt() != null) {
            // An already-rotated (or revoked) token came back: treat as stolen and kill the family.
            revokeFamily(cred.getAgentId(), cred.getFamilyId(), now);
            log.warn("XamPulse refresh token reuse detected for agent {}; family revoked", cred.getAgentId());
            return denied();
        }
        if (cred.getExpiresAt().isBefore(now)) {
            return denied();
        }
        Optional<XpAgent> agentOpt = agents.findById(cred.getAgentId());
        if (agentOpt.isEmpty() || !"ACTIVE".equals(agentOpt.get().getState())) {
            return denied();
        }
        XpAgent agent = agentOpt.get();
        cred.setRevokedAt(now);
        credentials.save(cred);
        String next = newToken();
        XpAgentCredential n = new XpAgentCredential();
        n.setAgentId(agent.getId());
        n.setFamilyId(cred.getFamilyId());
        n.setRefreshHash(AgentEnrollmentService.sha256Hex(next));
        n.setIssuedAt(now);
        n.setExpiresAt(now.plus(AgentEnrollmentService.REFRESH_TTL));
        credentials.save(n);
        String access = tokens.issue(agent.getInstanceId(), agent.getCloudAccountId());
        return new Refreshed(RefreshStatus.OK, new TokenPair(access, next, now.plus(AgentTokenService.TTL).toString()));
    }

    private static Refreshed denied() {
        return new Refreshed(RefreshStatus.DENIED, null);
    }

    private void revokeFamily(Long agentId, UUID family, Instant now) {
        for (XpAgentCredential c : credentials.findByAgentId(agentId)) {
            if (family.equals(c.getFamilyId()) && c.getRevokedAt() == null) {
                c.setRevokedAt(now);
                credentials.save(c);
            }
        }
    }

    /** The agent behind a valid access token, whatever its state; empty for a bad or expired token. */
    public Optional<XpAgent> authenticate(String accessToken) {
        return tokens.verify(accessToken).flatMap(c -> agents.findByCloudAccountIdAndInstanceId(c.tenantId(), c.instanceId()));
    }

    /**
     * Records the heartbeat and answers with the pending command. A paused or revoked agent still authenticates here
     * (its token is valid for up to 24 h) and is told to uninstall; ingestion is blocked separately.
     */
    @Transactional
    public HeartbeatResponse heartbeat(XpAgent agent, HeartbeatRequest hb) {
        if ("PAUSED".equals(agent.getState()) || "REVOKED".equals(agent.getState())) {
            return new HeartbeatResponse("UNINSTALL", agent.getConfigVersion(), null);
        }
        agent.setLastSeenAt(clock.instant());
        if (hb != null) {
            if (hb.agentVersion() != null) {
                agent.setAgentVersion(clip(hb.agentVersion(), 32));
            }
            if (hb.collectorVersion() != null) {
                agent.setCollectorVersion(clip(hb.collectorVersion(), 32));
            }
            agent.setHealthJson(healthJson(hb));
        }
        String command = agent.getPendingCommand();
        String upgradeTo = null;
        if (command == null || command.isBlank()) {
            String latest = props.getSetup().getLatestAgentVersion();
            // Auto-upgrade is off until a release signing key is pinned; until then upgrades are manual.
            if (props.getSetup().autoUpgradeAllowed() && latest != null && !latest.isBlank() && hb != null && hb.agentVersion() != null && !latest.equals(hb.agentVersion())
                    && !"dev".equals(hb.agentVersion())) {
                command = "UPGRADE";
                upgradeTo = latest;
            } else {
                command = "NONE";
            }
        } else if ("RELOAD_CONFIG".equals(command)) {
            agent.setPendingCommand(null); // one-shot
        }
        agents.save(agent);
        return new HeartbeatResponse(command, agent.getConfigVersion(), upgradeTo);
    }

    static final int MAX_SOURCES = 32;
    static final int MAX_ERRORS = 10;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Bounded, control-character-free JSON of the heartbeat health fields; null when the heartbeat carried none. */
    static String healthJson(HeartbeatRequest hb) {
        if (hb == null || (hb.sourcesStatus() == null && hb.errors() == null && hb.queue() == null)) {
            return null;
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (hb.sourcesStatus() != null) {
            List<java.util.Map<String, String>> sources = new java.util.ArrayList<>();
            for (HeartbeatRequest.SourceStatus s : hb.sourcesStatus()) {
                if (s == null || s.source() == null || sources.size() >= MAX_SOURCES) {
                    continue;
                }
                java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("source", clip(s.source(), 64));
                m.put("state", s.state() == null ? "" : clip(s.state(), 16));
                if (s.error() != null) {
                    m.put("error", clip(s.error(), 200));
                }
                sources.add(m);
            }
            out.put("sourcesStatus", sources);
        }
        if (hb.errors() != null) {
            out.put("errors", hb.errors().stream().filter(java.util.Objects::nonNull).limit(MAX_ERRORS).map(e -> clip(e, 200)).toList());
        }
        if (hb.queue() != null) {
            java.util.Map<String, Object> q = new java.util.LinkedHashMap<>();
            q.put("bytes", hb.queue().bytes());
            q.put("dropped", hb.queue().dropped());
            out.put("queue", q);
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** Null when the agent is not allowed to read configuration. */
    public AgentConfig config(XpAgent agent) {
        if (!"ACTIVE".equals(agent.getState())) {
            return null;
        }
        return configService.render(agent);
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("[\\p{Cntrl}]", "");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
