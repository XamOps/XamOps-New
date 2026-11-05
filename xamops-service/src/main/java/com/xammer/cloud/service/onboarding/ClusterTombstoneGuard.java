package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.repository.ClusterOffboardingTombstoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tombstone lookup for both ingest row-creators (design §2.2, §4.3, F-14). A cluster that was
 * deliberately offboarded has a {@code cluster_offboarding_tombstone} row; while it exists, a stale
 * agent must not silently recreate the cluster. Onboarding pre-registration clears the tombstone on
 * purpose, so a real re-connect works.
 */
@Component
public class ClusterTombstoneGuard {

    private static final Logger logger = LoggerFactory.getLogger(ClusterTombstoneGuard.class);

    /** TOMBSTONE_BLOCKED audit is throttled to one per cluster per hour (design §4.3). */
    private static final Duration AUDIT_THROTTLE = Duration.ofHours(1);

    private final ClusterOffboardingTombstoneRepository tombstoneRepository;
    private final Map<String, Instant> lastAudited = new ConcurrentHashMap<>();

    public ClusterTombstoneGuard(ClusterOffboardingTombstoneRepository tombstoneRepository) {
        this.tombstoneRepository = tombstoneRepository;
    }

    /** True when (account, cluster) is tombstoned and ingest must refuse to (re)create the row. */
    public boolean blocked(Long cloudAccountPk, String clusterName) {
        if (cloudAccountPk == null || clusterName == null) {
            return false;
        }
        boolean blocked = tombstoneRepository.existsByCloudAccountPkAndClusterName(cloudAccountPk, clusterName);
        if (blocked) {
            logger.warn("offboarded cluster; re-connect required: account={} cluster={}",
                    cloudAccountPk, clusterName);
        }
        return blocked;
    }

    /** Whether a TOMBSTONE_BLOCKED audit should be written now (throttled to 1/cluster/hour). */
    public boolean shouldAudit(Long cloudAccountPk, String clusterName) {
        String key = cloudAccountPk + "/" + clusterName;
        Instant now = Instant.now();
        Instant last = lastAudited.get(key);
        if (last == null || last.isBefore(now.minus(AUDIT_THROTTLE))) {
            lastAudited.put(key, now);
            return true;
        }
        return false;
    }
}
