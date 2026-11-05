package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.service.onboarding.CloudNodegroupsReader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP-16 (design §8.1, F-9): the reader returns a fresh row parsed, ignores unknown fields, and
 * returns empty for a stale or malformed row.
 */
class CloudNodegroupsReaderTest {

    private static final String DT = "cloud-nodegroups";

    private KubernetesClusterConfig config() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(100L);
        return c;
    }

    private AgentDataCacheRepository repoWith(AgentDataCache row) {
        AgentDataCacheRepository repo = mock(AgentDataCacheRepository.class);
        when(repo.findByClusterConfigIdAndDataType(eq(100L), eq(DT)))
                .thenReturn(row == null ? Optional.empty() : Optional.of(row));
        return repo;
    }

    private AgentDataCache row(String json, LocalDateTime collectedAt) {
        AgentDataCache r = mock(AgentDataCache.class);
        when(r.getDataJson()).thenReturn(json);
        when(r.getCollectedAt()).thenReturn(collectedAt);
        return r;
    }

    @Test
    void freshRow_isParsed_unknownFieldsIgnored() {
        String json = """
            {"collectedAt":"2026-10-30T12:00:00Z","somethingNew":1,
             "nodegroups":[{"name":"ng-1","status":"ACTIVE","capacityType":"ON_DEMAND",
               "min":1,"desired":4,"max":4,"asgNames":["eks-ng-1"],"amiType":"AL2023","instanceTypes":["m5.large"]}]}""";
        CloudNodegroupsReader reader = new CloudNodegroupsReader(
                repoWith(row(json, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5))));
        Optional<CloudNodegroupsReader.Result> r = reader.read(config(), Duration.ofMinutes(30));
        assertThat(r).isPresent();
        assertThat(r.get().nodegroups()).hasSize(1);
        CloudNodegroupsReader.Nodegroup ng = r.get().nodegroups().get(0);
        assertThat(ng.name()).isEqualTo("ng-1");
        assertThat(ng.min()).isEqualTo(1);
        assertThat(ng.desired()).isEqualTo(4);
        assertThat(ng.asgNames()).containsExactly("eks-ng-1");
    }

    @Test
    void staleRow_isEmpty() {
        String json = "{\"nodegroups\":[]}";
        CloudNodegroupsReader reader = new CloudNodegroupsReader(
                repoWith(row(json, LocalDateTime.now(ZoneOffset.UTC).minusHours(2))));
        assertThat(reader.read(config(), Duration.ofMinutes(30))).isEmpty();
    }

    @Test
    void malformedRow_isEmpty() {
        CloudNodegroupsReader reader = new CloudNodegroupsReader(
                repoWith(row("{not json", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))));
        assertThat(reader.read(config(), Duration.ofMinutes(30))).isEmpty();
    }

    @Test
    void noRow_isEmpty() {
        CloudNodegroupsReader reader = new CloudNodegroupsReader(repoWith(null));
        assertThat(reader.read(config(), Duration.ofMinutes(30))).isEmpty();
    }
}
