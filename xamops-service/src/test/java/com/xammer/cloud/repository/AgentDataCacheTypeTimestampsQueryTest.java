package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentDataCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 slice test for the capabilities projection over {@code agent_data_cache} (design §11.3):
 * type, freshness, size and agent version per row, for the requested clusters only.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class AgentDataCacheTypeTimestampsQueryTest {

    @Autowired
    AgentDataCacheRepository repository;

    private void row(long configId, String type, LocalDateTime collectedAt, int items, String version) {
        AgentDataCache c = new AgentDataCache(configId, type, "{\"large\":\"payload\"}", items);
        c.setCollectedAt(collectedAt);
        c.setAgentVersion(version);
        repository.save(c);
    }

    @Test
    void returnsTypeTimestampsForTheRequestedClustersOnly() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 10, 9, 26, 41);
        row(10L, "k8s-resources", at, 1, "v2.1.0");
        row(10L, "autoscaling-metrics", at.minusMinutes(2), 1, "1.0.0-autoscaler");
        row(11L, "k8s-resources", at, 1, "v2.1.0");
        row(12L, "k8s-resources", at, 1, "v2.1.0");

        List<AgentDataCacheRepository.TypeTimestamp> rows = repository.findTypeTimestampsByClusterConfigIdIn(List.of(10L, 11L));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(AgentDataCacheRepository.TypeTimestamp::getClusterConfigId).containsOnly(10L, 11L);
        assertThat(rows).filteredOn(r -> r.getClusterConfigId() == 10L && "autoscaling-metrics".equals(r.getDataType()))
                .singleElement().satisfies(r -> {
                    assertThat(r.getCollectedAt()).isEqualTo(at.minusMinutes(2));
                    assertThat(r.getItemCount()).isEqualTo(1);
                    assertThat(r.getAgentVersion()).isEqualTo("1.0.0-autoscaler");
                });
    }
}
