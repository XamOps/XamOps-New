package com.xammer.cloud.service.spot;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadKeysTest {

    private static final Set<String> DEPLOYMENTS = Set.of("team-a/web", "team-a/api-v2");

    @Test
    void aReplicaSetOfAKnownDeploymentIsThatDeployment() {
        assertThat(WorkloadKeys.normalize("team-a", "ReplicaSet", "web-5d9c8f7b6", "web-5d9c8f7b6-x1", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "Deployment", "web"));
        assertThat(WorkloadKeys.normalize("team-a", "ReplicaSet", "api-v2-7f8d9", "p", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "Deployment", "api-v2"));
    }

    @Test
    void aReplicaSetWithoutAMatchingDeploymentStaysAReplicaSet() {
        assertThat(WorkloadKeys.normalize("team-a", "ReplicaSet", "batch-6c7d8", "p", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "ReplicaSet", "batch-6c7d8"));
        assertThat(WorkloadKeys.normalize("team-b", "ReplicaSet", "web-5d9c8f7b6", "p", DEPLOYMENTS))
                .as("same name, other namespace")
                .isEqualTo(new WorkloadKeys.Key("team-b", "ReplicaSet", "web-5d9c8f7b6"));
    }

    @Test
    void aNameWithoutADashStaysAReplicaSet() {
        assertThat(WorkloadKeys.normalize("team-a", "ReplicaSet", "web", "p", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "ReplicaSet", "web"));
    }

    @Test
    void anOwnerlessPodIsItsOwnWorkload() {
        assertThat(WorkloadKeys.normalize("team-a", "", "", "debug-shell", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "Pod", "debug-shell"));
        assertThat(WorkloadKeys.normalize("team-a", null, null, "debug-shell", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("team-a", "Pod", "debug-shell"));
    }

    @Test
    void otherKindsAreUnchanged() {
        assertThat(WorkloadKeys.normalize("db", "StatefulSet", "pg", "pg-0", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("db", "StatefulSet", "pg"));
        assertThat(WorkloadKeys.normalize("db", "Job", "migrate-1", "migrate-1-abc", DEPLOYMENTS))
                .isEqualTo(new WorkloadKeys.Key("db", "Job", "migrate-1"));
    }
}
