package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every {@link ClusterDataPurgeParticipant} (in Spring {@code @Order}) for a cluster before its
 * config row is deleted (design §4.5). Each participant runs independently; a participant failure
 * aborts the purge with the participant name so the caller can return {@code PURGE_FAILED} and the
 * user can retry (idempotently).
 */
@Service
public class ClusterDataPurger {

    private static final Logger logger = LoggerFactory.getLogger(ClusterDataPurger.class);

    private final List<ClusterDataPurgeParticipant> participants;

    public ClusterDataPurger(List<ClusterDataPurgeParticipant> participants) {
        this.participants = participants;
    }

    public static class PurgeFailedException extends RuntimeException {
        private final String participant;

        public PurgeFailedException(String participant, Throwable cause) {
            super("Purge participant failed: " + participant, cause);
            this.participant = participant;
        }

        public String getParticipant() {
            return participant;
        }
    }

    /** Run every participant; return the per-participant deleted counts. */
    public Map<String, Integer> purge(KubernetesClusterConfig config, CloudAccount owner) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        for (ClusterDataPurgeParticipant participant : participants) {
            try {
                int removed = participant.purge(config, owner);
                deleted.put(participant.name(), removed);
            } catch (RuntimeException e) {
                logger.error("Offboard purge participant {} failed for cluster {}: {}",
                        participant.name(), config.getClusterName(), e.toString());
                throw new PurgeFailedException(participant.name(), e);
            }
        }
        return deleted;
    }
}
