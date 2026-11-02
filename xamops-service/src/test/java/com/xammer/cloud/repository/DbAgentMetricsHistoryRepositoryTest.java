package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbAgentMetricsHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-5: {@link DbAgentMetricsHistoryRepository#purgeOlderThan} deletes only rows older than the
 * cutoff, and the composite index matching the timeseries finder
 * (db_agent_id, data_type, collected_at) exists on the schema.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class DbAgentMetricsHistoryRepositoryTest {

    @Autowired
    DbAgentMetricsHistoryRepository repository;

    @Autowired
    DataSource dataSource;

    private DbAgentMetricsHistory row(long agentId, LocalDateTime collectedAt) {
        DbAgentMetricsHistory h = new DbAgentMetricsHistory();
        h.setDbAgentId(agentId);
        h.setDataType("db-instance-metrics");
        h.setDataJson("[]");
        h.setItemCount(0);
        h.setCollectedAt(collectedAt);
        return h;
    }

    @Test
    void purgeOlderThanDeletesOnlyOlderRows() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 1, 0, 0);
        repository.saveAll(List.of(
                row(1L, cutoff.minusDays(5)),
                row(1L, cutoff.minusHours(1)),
                row(1L, cutoff.plusHours(1)),
                row(1L, cutoff.plusDays(3))));
        repository.flush();

        int deleted = repository.purgeOlderThan(cutoff);

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findAll())
                .allSatisfy(r -> assertThat(r.getCollectedAt()).isAfter(cutoff));
    }

    @Test
    void compositeIndexCoversAgentTypeCollected() throws Exception {
        // Column set + order of the index, read via JDBC metadata so it is H2-version independent.
        Map<String, SortedMap<Integer, String>> byIndex = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData()
                     .getIndexInfo(null, null, "DB_AGENT_METRICS_HISTORY", false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName == null || column == null) {
                    continue;
                }
                byIndex.computeIfAbsent(indexName, k -> new TreeMap<>())
                        .put(rs.getInt("ORDINAL_POSITION"), column.toUpperCase());
            }
        }

        boolean hasComposite = byIndex.values().stream()
                .map(cols -> new ArrayList<>(cols.values()))
                .anyMatch(cols -> cols.equals(List.of("DB_AGENT_ID", "DATA_TYPE", "COLLECTED_AT")));

        assertThat(hasComposite)
                .as("composite index (db_agent_id, data_type, collected_at) must exist")
                .isTrue();
    }
}
