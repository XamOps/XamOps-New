package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.service.platform.ClickHouseWrites;
import com.xammer.cloud.xampulse.service.query.EventQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Writes a single row into xp_events. The only place platform code emits timeline events (alerts, anomalies, EC2
 * state). The kind is checked against {@link EventQueryService#KINDS} so a typo can never land an unreadable row.
 */
@Service("xpEventWriter")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class EventWriter {

    static final List<String> COLUMNS = List.of("tenant_id", "ts", "kind", "resource", "title", "detail");

    private final ClickHouseWrites writes;

    public EventWriter(ClickHouseWrites writes) {
        this.writes = writes;
    }

    public void record(long tenantId, String kind, String resource, String title, String detail, Instant ts) throws SQLException {
        if (!EventQueryService.KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown event kind: " + kind);
        }
        List<Object> row = List.of(tenantId, Timestamp.from(ts), kind,
                resource == null ? "" : resource, title == null ? "" : title, detail == null ? "" : detail);
        writes.insert("xp_events", COLUMNS, List.of(row));
    }
}
