package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpAccountRegion;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpAccountState;
import com.xammer.cloud.xampulse.dto.XpStackStatus;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAccountConfigRepository;
import com.xammer.cloud.xampulse.repository.XpAccountRegionRepository;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Follows the customer's stacks (one DescribeStacks per region row) under the connection role, records their state
 * and drives the account state machine: stack OK -> VERIFYING, agent heartbeat seen and first metrics/logs landed in
 * every reporting region -> CONNECTED, a failed stack or
 * no agent for a long time -> DEGRADED. Reads only; it never changes anything in the customer's account.
 */
@Service("xpStackWatcher")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseStackWatcher {

    private static final Logger log = LoggerFactory.getLogger(XamPulseStackWatcher.class);
    private static final Set<XpAccountState> WATCHED = EnumSet.of(XpAccountState.PLANNED, XpAccountState.DEPLOYING,
            XpAccountState.VERIFYING, XpAccountState.CONNECTED, XpAccountState.DEGRADED);
    /** A connected account with no heartbeat for this long is DEGRADED. */
    static final Duration SILENT_AFTER = Duration.ofMinutes(15);
    static final int ARCHIVE_FAILURE_LIMIT = 3;

    private final XpAccountConfigRepository configs;
    private final XpAccountRegionRepository regions;
    private final XpAgentRepository agents;
    private final CloudAccountRepository accounts;
    private final StackReader stacks;
    private final SchedulingEnvironmentConfig scheduling;
    private final TelemetryProbe probe;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public XamPulseStackWatcher(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                                CloudAccountRepository accounts, StackReader stacks, SchedulingEnvironmentConfig scheduling,
                                TelemetryProbe probe) {
        this(configs, regions, agents, accounts, stacks, scheduling, probe, Clock.systemUTC());
    }

    public XamPulseStackWatcher(XpAccountConfigRepository configs, XpAccountRegionRepository regions, XpAgentRepository agents,
                                CloudAccountRepository accounts, StackReader stacks, SchedulingEnvironmentConfig scheduling,
                                TelemetryProbe probe, Clock clock) {
        this.configs = configs;
        this.regions = regions;
        this.agents = agents;
        this.accounts = accounts;
        this.stacks = stacks;
        this.scheduling = scheduling;
        this.probe = probe;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${xampulse.setup.watcher-interval-ms:30000}", initialDelayString = "${xampulse.setup.watcher-interval-ms:30000}")
    public void tick() {
        if (!scheduling.isSchedulingEnabled()) {
            return;
        }
        for (XpAccountConfig cfg : configs.findAll()) {
            if (!WATCHED.contains(SetupStateMachine.parse(cfg.getState()))) {
                continue;
            }
            try {
                accounts.findById(cfg.getCloudAccountId()).ifPresent(a -> refresh(a, cfg.getCloudAccountId()));
            } catch (RuntimeException e) {
                log.warn("XamPulse watcher failed for account {}: {}", cfg.getCloudAccountId(), e.toString());
            }
        }
    }

    /** Re-reads every region's stack for one account and advances the account state. Returns the statuses. */
    @Transactional
    public List<XpStackStatus> refresh(CloudAccount account, Long accountId) {
        XpAccountConfig cfg = configs.findByCloudAccountId(accountId).orElse(null);
        if (cfg == null) {
            return List.of();
        }
        List<XpStackStatus> out = new ArrayList<>();
        XpAccountState state = SetupStateMachine.parse(cfg.getState());
        boolean globalOk = false;
        boolean anyDeploying = false;
        for (XpAccountRegion row : regions.findByCloudAccountId(accountId)) {
            XpStackStatus st;
            try {
                st = apply(account, cfg, row);
            } catch (XamPulseException e) {
                row.setLastCheckedAt(clock.instant());
                row.setLastError(e.getMessage());
                regions.save(row);
                st = new XpStackStatus(row.getRegion(), row.getKind(), row.getStackName(), "UNKNOWN", e.getMessage(), List.of(), Map.of());
            }
            out.add(st);
            if ("GLOBAL".equals(row.getKind()) && "OK".equals(row.getState())) {
                globalOk = true;
            }
            anyDeploying |= "DEPLOYING".equals(row.getState());
        }

        List<XpAgent> agentRows = agents.findByCloudAccountId(accountId);
        Instant now = clock.instant();
        // Only ask the telemetry store when it can change the answer (entering CONNECTED), not on every tick.
        boolean needsData = globalOk && (state == XpAccountState.VERIFYING || state == XpAccountState.DEGRADED);
        boolean dataLanded = needsData && TelemetryRules.dataLandedEverywhere(agentRows, probe.landed(accountId), now);
        XpAccountState next = nextState(state, globalOk, anyDeploying, agentRows, now, dataLanded);
        if (state == XpAccountState.DEGRADED && next == XpAccountState.CONNECTED && cfg.getArchiveFailures() >= ARCHIVE_FAILURE_LIMIT) {
            next = XpAccountState.DEGRADED; // the archive writer marked it; do not flap back until archiving works again
        }
        if (next != state) {
            cfg.setState(SetupStateMachine.move(state, next).name());
            cfg.setUpdatedAt(clock.instant());
        }
        configs.save(cfg);
        return out;
    }

    /**
     * Pure rule for the account state; unit tested. A failed regional stack marks only that region failed, so it
     * does not appear here: the account stays CONNECTED while data flows.
     */
    static XpAccountState nextState(XpAccountState state, boolean globalOk, boolean anyDeploying,
                                    List<XpAgent> agentRows, Instant now, boolean dataLanded) {
        if (state == XpAccountState.DISCONNECTED || state == XpAccountState.NOT_SET_UP) {
            return state;
        }
        boolean heartbeat = agentRows.stream().anyMatch(a -> "ACTIVE".equals(a.getState()) && a.getLastSeenAt() != null
                && Duration.between(a.getLastSeenAt(), now).compareTo(SILENT_AFTER) <= 0);
        if (!globalOk) {
            if (anyDeploying) {
                return state == XpAccountState.PLANNED || state == XpAccountState.DEPLOYING || state == XpAccountState.VERIFYING
                        ? XpAccountState.DEPLOYING : state;
            }
            // The global stack is missing or failed after the account was up.
            return state == XpAccountState.CONNECTED || state == XpAccountState.VERIFYING ? XpAccountState.DEGRADED : state;
        }
        return switch (state) {
            case PLANNED, DEPLOYING -> XpAccountState.VERIFYING;
            case VERIFYING -> heartbeat && dataLanded ? XpAccountState.CONNECTED : XpAccountState.VERIFYING;
            case CONNECTED -> heartbeat ? XpAccountState.CONNECTED : XpAccountState.DEGRADED;
            case DEGRADED -> heartbeat && dataLanded ? XpAccountState.CONNECTED : XpAccountState.DEGRADED;
            default -> state;
        };
    }

    private XpStackStatus apply(CloudAccount account, XpAccountConfig cfg, XpAccountRegion row) {
        Optional<StackReader.Stack> found = stacks.describe(account, row.getRegion(), row.getStackName());
        row.setLastCheckedAt(clock.instant());
        if (found.isEmpty()) {
            row.setState("NONE");
            row.setLastError(null);
            regions.save(row);
            return new XpStackStatus(row.getRegion(), row.getKind(), row.getStackName(), "NOT_CREATED", null, List.of(), Map.of());
        }
        StackReader.Stack s = found.get();
        row.setState(StackReader.rowState(s.status()));
        row.setStackId(s.stackId());
        row.setLastError("FAILED".equals(row.getState()) ? (s.reason() == null ? s.status() : s.reason()) : null);
        String version = s.outputs().get("TemplateVersion");
        if (version != null) {
            row.setTemplateVersion(version);
        }
        regions.save(row);
        if ("GLOBAL".equals(row.getKind()) && "OK".equals(row.getState())) {
            cfg.setStackId(s.stackId());
            cfg.setArchiveRoleArn(s.outputs().get("ArchiveRoleArn"));
            String bucketRegion = s.outputs().get("BucketRegion");
            if (bucketRegion != null && "CREATE".equals(cfg.getBucketMode())) {
                cfg.setBucketRegion(bucketRegion);
            }
            if (version != null) {
                cfg.setTemplateVersion(version);
            }
            String key = s.outputs().get("KmsKeyArn");
            if (key != null && !key.isBlank() && "CREATE".equals(cfg.getBucketMode())) {
                cfg.setKmsKeyArn(key);
            }
        }
        return new XpStackStatus(row.getRegion(), row.getKind(), row.getStackName(), s.status(), s.reason(), s.events(), s.outputs());
    }
}
