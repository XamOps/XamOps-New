package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.config.ClickHouseConnections;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClickHouseWritesTest {

    /** Records the SQL compiled and every batch flushed, so the test can assert batching and binding. */
    private static final class Recorder {
        String sql;
        int batchesAdded;
        final List<Integer> executeBatchSizes = new ArrayList<>();
        final List<Object> boundValues = new ArrayList<>();
    }

    private JdbcClickHouseWrites writesOver(Recorder rec) throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        int[] pendingSinceLastExec = {0};
        doAnswer(inv -> { rec.boundValues.add(inv.getArgument(1)); return null; })
                .when(ps).setObject(anyInt(), any());
        doAnswer(inv -> { rec.batchesAdded++; pendingSinceLastExec[0]++; return null; }).when(ps).addBatch();
        when(ps.executeBatch()).thenAnswer(inv -> {
            rec.executeBatchSizes.add(pendingSinceLastExec[0]);
            pendingSinceLastExec[0] = 0;
            return new int[0];
        });
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenAnswer(inv -> { rec.sql = inv.getArgument(0); return ps; });
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        ClickHouseConnections connections = mock(ClickHouseConnections.class);
        when(connections.writer()).thenReturn(ds);
        return new JdbcClickHouseWrites(connections);
    }

    @Test
    void rejectsUnknownTable() throws Exception {
        JdbcClickHouseWrites w = writesOver(new Recorder());
        assertThrows(IllegalArgumentException.class,
                () -> w.insert("xp_secrets", List.of("tenant_id"), List.of(List.of(1L))));
    }

    @Test
    void rejectsColumnNotInTheWhitelist() throws Exception {
        JdbcClickHouseWrites w = writesOver(new Recorder());
        assertThrows(IllegalArgumentException.class,
                () -> w.insert("xp_baseline", List.of("tenant_id", "evil"), List.of(List.of(1L, 2L))));
    }

    @Test
    void rejectsRowWidthMismatch() throws Exception {
        JdbcClickHouseWrites w = writesOver(new Recorder());
        assertThrows(IllegalArgumentException.class,
                () -> w.insert("xp_events", List.of("tenant_id", "kind"), List.of(List.of(1L))));
    }

    @Test
    void buildsParameterisedSqlAndBindsEveryValue() throws Exception {
        Recorder rec = new Recorder();
        JdbcClickHouseWrites w = writesOver(rec);
        w.insert("xp_events", List.of("tenant_id", "ts", "kind", "resource", "title", "detail"),
                List.of(List.of(7L, Timestamp.from(java.time.Instant.EPOCH), "ALERT", "i-1", "t", "d")));
        assertEquals("INSERT INTO xp_events (tenant_id, ts, kind, resource, title, detail) VALUES (?, ?, ?, ?, ?, ?)", rec.sql);
        assertEquals(1, rec.batchesAdded);
        assertEquals(6, rec.boundValues.size());
        assertTrue(rec.boundValues.contains("ALERT"));
    }

    @Test
    void emptyRowsIsANoOp() throws Exception {
        Recorder rec = new Recorder();
        JdbcClickHouseWrites w = writesOver(rec);
        w.insert("xp_baseline", BaselineColumns.COLS, List.of());
        assertNull(rec.sql);
        assertEquals(0, rec.batchesAdded);
    }

    @Test
    void chunksAtTenThousandRows() throws Exception {
        Recorder rec = new Recorder();
        JdbcClickHouseWrites w = writesOver(rec);
        int rows = JdbcClickHouseWrites.CHUNK * 2 + 5;
        List<List<Object>> batch = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            batch.add(List.of(1L, "m", "i-1", i % 168, 1.0, 0.5, Timestamp.from(java.time.Instant.EPOCH)));
        }
        w.insert("xp_baseline", BaselineColumns.COLS, batch);
        assertEquals(rows, rec.batchesAdded);
        // two full chunks then a final flush of the remainder
        assertEquals(List.of(JdbcClickHouseWrites.CHUNK, JdbcClickHouseWrites.CHUNK, 5), rec.executeBatchSizes);
    }

    private static final class BaselineColumns {
        static final List<String> COLS = List.of("tenant_id", "metric", "instance_id", "hour_of_week", "med", "mad", "updated_at");
    }
}
