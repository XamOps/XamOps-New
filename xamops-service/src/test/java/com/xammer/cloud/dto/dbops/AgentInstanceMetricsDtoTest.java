package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The extended COL-A1 Postgres counters (item 8/11) round-trip from the agent's JSON tags into
 * {@link AgentInstanceMetricsDto}, including the nullable WAL timing fields (present on PG&lt;18,
 * absent/null on PG18+).
 */
class AgentInstanceMetricsDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesExtendedCountersWithWalTiming() throws Exception {
        String json = """
            {
              "dsnAlias": "db-0", "dbEngine": "postgres", "dbHost": "pg.internal",
              "currentConnections": 42, "maxConnections": 100,
              "cacheHitRatio": 0.985, "blksHit": 9850, "blksRead": 150,
              "tempFiles": 3, "tempBytes": 1048576,
              "xactCommit": 1000, "xactRollback": 5,
              "checkpointsTimed": 12, "checkpointsReq": 2,
              "checkpointWriteTime": 345.6, "buffersCheckpoint": 789,
              "walRecords": 50000, "walFpi": 120, "walBytes": 6553600, "walBuffersFull": 7,
              "walWriteTime": 12.5, "walSyncTime": 3.25,
              "datfrozenxidAge": 150000000,
              "innodbDeadlocks": 4
            }
            """;

        AgentInstanceMetricsDto m = mapper.readValue(json, AgentInstanceMetricsDto.class);

        assertThat(m.getCacheHitRatio()).isCloseTo(0.985, within(1e-9));
        assertThat(m.getBlksHit()).isEqualTo(9850);
        assertThat(m.getBlksRead()).isEqualTo(150);
        assertThat(m.getTempFiles()).isEqualTo(3);
        assertThat(m.getTempBytes()).isEqualTo(1048576);
        assertThat(m.getXactCommit()).isEqualTo(1000);
        assertThat(m.getXactRollback()).isEqualTo(5);
        assertThat(m.getCheckpointsTimed()).isEqualTo(12);
        assertThat(m.getCheckpointsReq()).isEqualTo(2);
        assertThat(m.getCheckpointWriteTime()).isCloseTo(345.6, within(1e-9));
        assertThat(m.getBuffersCheckpoint()).isEqualTo(789);
        assertThat(m.getWalRecords()).isEqualTo(50000);
        assertThat(m.getWalFpi()).isEqualTo(120);
        assertThat(m.getWalBytes()).isEqualTo(6553600);
        assertThat(m.getWalBuffersFull()).isEqualTo(7);
        assertThat(m.getWalWriteTime()).isCloseTo(12.5, within(1e-9));
        assertThat(m.getWalSyncTime()).isCloseTo(3.25, within(1e-9));
        assertThat(m.getDatfrozenxidAge()).isEqualTo(150000000L);
        assertThat(m.getInnodbDeadlocks()).isEqualTo(4);
    }

    @Test
    void walTimingIsNullOnPg18() throws Exception {
        // PG18+ removed wal_write_time / wal_sync_time — the agent omits them, so the DTO is null.
        String json = """
            {
              "dsnAlias": "db-0", "dbEngine": "postgres",
              "walRecords": 10, "walFpi": 1, "walBytes": 2048, "walBuffersFull": 0
            }
            """;

        AgentInstanceMetricsDto m = mapper.readValue(json, AgentInstanceMetricsDto.class);

        assertThat(m.getWalWriteTime()).isNull();
        assertThat(m.getWalSyncTime()).isNull();
        assertThat(m.getWalRecords()).isEqualTo(10);
    }
}
