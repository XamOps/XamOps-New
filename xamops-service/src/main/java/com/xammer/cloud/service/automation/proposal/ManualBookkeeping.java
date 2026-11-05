package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The two pieces of bookkeeping the agent endpoints write on the cluster row, always through the
 * three {@code @Modifying} repository methods (never {@code save(config)}) and each in its own small
 * {@code REQUIRES_NEW} transaction, so a bookkeeping failure can never fail a policy serve or a
 * heartbeat and never leaves the caller's transaction rollback-only:
 *
 * <ul>
 *   <li>{@link #recordPolicyServed}: which approval mode the automation agent was last served and when
 *       (design 3.3 layer 1). Written only for a CLUSTER+AUTOMATION principal and only for a cluster
 *       that is, or ever was, Manual, so an Autonomous cluster gets no extra write (NFR-2).</li>
 *   <li>{@link #recordHeartbeat}: the leader's advertised {@code features} (for example
 *       {@code manual-v1}) and {@code drainTimeoutSeconds} (design 3.8). Written only for a leader
 *       beat of a CLUSTER+AUTOMATION principal, never via the {@code -autoscaler} version-suffix
 *       fallback and never from a standby replica.</li>
 * </ul>
 */
@Component
public class ManualBookkeeping {

    private static final Logger log = LoggerFactory.getLogger(ManualBookkeeping.class);

    static final Duration SERVE_WRITE_THROTTLE = Duration.ofSeconds(30);
    static final Duration FEATURES_WRITE_THROTTLE = Duration.ofMinutes(5);
    private static final Pattern FEATURE_TOKEN = Pattern.compile("^[a-z0-9.-]{1,32}$");
    private static final int MAX_FEATURES = 16;
    private static final int FEATURES_COLUMN_LENGTH = 255;

    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final Clock clock;

    public ManualBookkeeping(KubernetesClusterConfigRepository clusterConfigRepository) {
        this(clusterConfigRepository, Clock.systemUTC());
    }

    ManualBookkeeping(KubernetesClusterConfigRepository clusterConfigRepository, Clock clock) {
        this.clusterConfigRepository = clusterConfigRepository;
        this.clock = clock;
    }

    static boolean isAutomationRole(AgentPrincipal principal) {
        return principal != null && principal.getScope() == AgentPrincipal.Scope.CLUSTER
                && principal.getRole() == AgentPrincipal.AgentRole.AUTOMATION;
    }

    /** Serve-side bookkeeping. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPolicyServed(KubernetesClusterConfig config, AgentPrincipal principal) {
        try {
            if (config == null || config.getId() == null || !isAutomationRole(principal)) {
                return;
            }
            String current = config.getAutomationApprovalMode();
            String served = config.getPolicyServedApprovalMode();
            boolean manual = ApprovalModeReader.MANUAL.equals(current);
            if (!manual && served == null) {
                return; // never been Manual: no extra write
            }
            String newServed = manual ? ApprovalModeReader.MANUAL : null;
            Instant now = clock.instant();
            Instant at = config.getPolicyServedAt();
            boolean changed = !Objects.equals(newServed, served);
            if (changed || at == null || at.isBefore(now.minus(SERVE_WRITE_THROTTLE))) {
                clusterConfigRepository.recordPolicyServed(config.getId(), newServed, now);
            }
        } catch (RuntimeException e) {
            log.warn("Manual mode: recording the served policy for cluster config {} failed: {}",
                    config == null ? null : config.getId(), e.getMessage());
        }
    }

    /** Heartbeat-side bookkeeping. Never throws; an invalid {@code features} list is simply not stored. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordHeartbeat(KubernetesClusterConfig config, AgentPrincipal principal, Map<String, Object> body) {
        try {
            if (config == null || config.getId() == null || body == null || !isAutomationRole(principal)) {
                return;
            }
            if (!Boolean.TRUE.equals(body.get("leader"))) {
                return; // a standby, or an older agent that sends no leader field, writes nothing
            }
            String features = validFeatures(body.get("features"));
            Integer drainTimeout = validDrainTimeout(body.get("drainTimeoutSeconds"));
            Instant now = clock.instant();
            Instant seenAt = config.getAutomationAgentFeaturesSeenAt();
            boolean changed = !Objects.equals(features, config.getAutomationAgentFeatures())
                    || !Objects.equals(drainTimeout, config.getAutomationAgentDrainTimeoutSeconds());
            if (changed || seenAt == null || seenAt.isBefore(now.minus(FEATURES_WRITE_THROTTLE))) {
                clusterConfigRepository.recordAgentFeatures(config.getId(), features, now, drainTimeout);
            }
        } catch (RuntimeException e) {
            log.warn("Manual mode: recording agent features for cluster config {} failed: {}",
                    config == null ? null : config.getId(), e.getMessage());
        }
    }

    /** A comma list of valid tokens, or null when the field is absent or invalid (logged at debug). */
    static String validFeatures(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_FEATURES) {
            if (raw != null) {
                log.debug("Heartbeat features ignored: not a list of 1..{} entries", MAX_FEATURES);
            }
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s) || !FEATURE_TOKEN.matcher(s).matches()) {
                log.debug("Heartbeat features ignored: an entry is not a valid token");
                return null;
            }
            if (!tokens.contains(s)) {
                tokens.add(s);
            }
        }
        String joined = String.join(",", tokens);
        return joined.length() > FEATURES_COLUMN_LENGTH ? null : joined;
    }

    /** 60..86400, else null (the backend then assumes the default). */
    static Integer validDrainTimeout(Object raw) {
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            long v = n.longValue();
            if (v >= 60 && v <= 86_400) {
                return (int) v;
            }
        }
        return null;
    }
}
