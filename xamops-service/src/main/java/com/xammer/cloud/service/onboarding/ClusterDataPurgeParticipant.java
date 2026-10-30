package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;

/**
 * A pluggable step in offboard data purge (design §4.5, FR-8.3). Each participant deletes one data
 * domain for a cluster and reports how many rows it removed, so the offboard response's
 * {@code deleted} map and the audit can name each. Implementations must be idempotent: a retry after
 * a partial failure resumes from the remaining rows.
 */
public interface ClusterDataPurgeParticipant {

    /** A stable name for the response "deleted" map and the audit. */
    String name();

    /** Delete this participant's rows for the cluster; return the number removed. Idempotent. */
    int purge(KubernetesClusterConfig config, CloudAccount owner);
}
