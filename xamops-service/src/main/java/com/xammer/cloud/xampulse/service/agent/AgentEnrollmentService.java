package com.xammer.cloud.xampulse.service.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.domain.XpAgentCredential;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.dto.agent.EnrollPending;
import com.xammer.cloud.xampulse.dto.agent.EnrollRequest;
import com.xammer.cloud.xampulse.dto.agent.EnrollResponse;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentCredentialRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.platform.AuditService;
import com.xammer.cloud.xampulse.service.setup.InstanceCandidateService;
import com.xammer.cloud.xampulse.service.setup.SetupStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns an identity proof into per-instance credentials, or says no. The answers are deliberately thin: success,
 * pending (202, the inventory has not seen the instance yet), or rejected (403 with no reason). The reason is logged
 * for operators and never sent, so a probe learns nothing about which accounts or instances exist.
 */
@Service("xpAgentEnrollmentService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AgentEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentEnrollmentService.class);
    static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Set<XpAccountState> ACTIVE = EnumSet.of(XpAccountState.PLANNED, XpAccountState.DEPLOYING,
            XpAccountState.VERIFYING, XpAccountState.CONNECTED, XpAccountState.DEGRADED);
    static final int MAX_ENROLLS_PER_INSTANCE_PER_MINUTE = 6;

    public sealed interface Result permits Success, Pending, Rejected, TooMany {}
    public record Success(EnrollResponse body) implements Result {}
    public record Pending(EnrollPending body) implements Result {}
    public record Rejected() implements Result {}
    public record TooMany() implements Result {}

    private final EnrollmentNonceService nonces;
    private final StsIdentityVerifier verifier;
    private final CloudAccountRepository accounts;
    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final XpAgentCredentialRepository credentials;
    private final AgentTokenService tokens;
    private final AgentConfigService configService;
    private final InstanceCandidateService candidates;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, AtomicInteger> perInstance = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(50_000).build();

    @org.springframework.beans.factory.annotation.Autowired
    public AgentEnrollmentService(EnrollmentNonceService nonces, StsIdentityVerifier verifier, CloudAccountRepository accounts,
                                  XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                                  XpAgentCredentialRepository credentials, AgentTokenService tokens, AgentConfigService configService,
                                  InstanceCandidateService candidates, AuditService audit) {
        this(nonces, verifier, accounts, configs, regions, agents, credentials, tokens, configService, candidates, audit, Clock.systemUTC());
    }

    AgentEnrollmentService(EnrollmentNonceService nonces, StsIdentityVerifier verifier, CloudAccountRepository accounts,
                           XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                           XpAgentCredentialRepository credentials, AgentTokenService tokens, AgentConfigService configService,
                           InstanceCandidateService candidates, AuditService audit, Clock clock) {
        this.nonces = nonces;
        this.verifier = verifier;
        this.accounts = accounts;
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
        this.credentials = credentials;
        this.tokens = tokens;
        this.configService = configService;
        this.candidates = candidates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Result enroll(EnrollRequest req, String ip) {
        if (req == null || req.region() == null || !req.region().matches("^[a-z]{2}(-[a-z]+)+-\\d$")) {
            return new Rejected();
        }
        if (!nonces.consume(req.nonce(), req.region())) {
            log.info("XamPulse enrol refused: nonce unknown, used or for another region");
            return new Rejected();
        }
        StsIdentityVerifier.Identity id;
        try {
            id = verifier.verify(req);
        } catch (StsIdentityVerifier.Rejected r) {
            log.info("XamPulse enrol refused: {}", r.getMessage());
            return new Rejected();
        }
        AtomicInteger n = perInstance.get(id.accountId() + "/" + id.instanceId(), k -> new AtomicInteger());
        if (n.incrementAndGet() > MAX_ENROLLS_PER_INSTANCE_PER_MINUTE) {
            return new TooMany();
        }

        Decision d = decide(id.accountId(), req.region(), id.instanceId());
        if (d.outcome == Outcome.OUT) {
            log.info("XamPulse enrol refused: instance not in scope (account {} region {})", id.accountId(), req.region());
            return new Rejected();
        }
        if (d.outcome == Outcome.PENDING) {
            return new Pending(new EnrollPending(60, "PENDING_TAG_SYNC"));
        }

        Instant now = clock.instant();
        Long accountId = d.account.getId();
        XpAgent agent = agents.findByCloudAccountIdAndInstanceId(accountId, id.instanceId()).orElseGet(() -> {
            XpAgent a = new XpAgent();
            a.setCloudAccountId(accountId);
            a.setInstanceId(id.instanceId());
            a.setCollectionLevel("T1");
            a.setConfigVersion(1);
            return a;
        });
        boolean first = agent.getId() == null;
        if ("PAUSED".equals(agent.getState()) || "REVOKED".equals(agent.getState())) {
            log.info("XamPulse enrol refused: agent paused or revoked (account {})", accountId);
            return new Rejected();
        }
        agent.setRegion(req.region());
        agent.setState("ACTIVE");
        agent.setAgentVersion(clip(req.agentVersion(), 32));
        agent.setOs(clip(req.os(), 16));
        agent.setLastSeenAt(now);
        agent.setPendingCommand(null);
        agent = agents.save(agent);

        // A new enrolment replaces every earlier credential of this instance.
        for (XpAgentCredential c : credentials.findByAgentId(agent.getId())) {
            if (c.getRevokedAt() == null) {
                c.setRevokedAt(now);
                credentials.save(c);
            }
        }
        String refresh = newRefreshToken();
        saveCredential(agent.getId(), UUID.randomUUID(), refresh, now);
        String access = tokens.issue(id.instanceId(), accountId);
        audit.recordForUser(null, accountId, first ? "agent.enroll" : "agent.re-enroll", id.instanceId(), ip);
        return new Success(new EnrollResponse(access, refresh, now.plus(AgentTokenService.TTL).toString(), configService.render(agent)));
    }

    enum Outcome { IN, PENDING, OUT }

    record Decision(Outcome outcome, CloudAccount account) {}

    /** First connection of this AWS account whose plan covers the instance. */
    Decision decide(String awsAccountId, String region, String instanceId) {
        boolean pending = false;
        CloudAccount pendingAccount = null;
        for (CloudAccount a : accounts.findByAwsAccountId(awsAccountId)) {
            Optional<XpAccountConfig> cfg = configs.findByCloudAccountId(a.getId());
            if (cfg.isEmpty() || !ACTIVE.contains(SetupStateMachine.parse(cfg.get().getState()))) {
                continue;
            }
            Optional<XpAccountRegion> row = regions.findByCloudAccountIdAndRegion(a.getId(), region);
            if (row.isEmpty()) {
                continue;
            }
            String mode = String.valueOf(row.get().getTargetMode());
            if ("INSTANCES".equals(mode)) {
                if (InstanceCandidateService.targetIds(row.get()).contains(instanceId)) {
                    return new Decision(Outcome.IN, a);
                }
                continue;
            }
            // Tag and all-managed targeting depend on inventory, which may not have seen the instance yet.
            var found = candidates.inventoryLookup(a.getId(), instanceId);
            if (found.isPresent() && region.equals(found.get().seen().region())
                    && InstanceCandidateService.inScope(row.get(), found.get().seen(), found.get().ssmManaged())) {
                return new Decision(Outcome.IN, a);
            }
            pending = true;
            pendingAccount = a;
        }
        return pending ? new Decision(Outcome.PENDING, pendingAccount) : new Decision(Outcome.OUT, null);
    }

    String newRefreshToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    void saveCredential(Long agentId, UUID family, String refreshToken, Instant now) {
        XpAgentCredential c = new XpAgentCredential();
        c.setAgentId(agentId);
        c.setFamilyId(family);
        c.setRefreshHash(sha256Hex(refreshToken));
        c.setIssuedAt(now);
        c.setExpiresAt(now.plus(REFRESH_TTL));
        credentials.save(c);
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[\\p{Cntrl}]", "");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
