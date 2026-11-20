package com.xammer.cloud.service.autospotting.ledger.prices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * {@code gcp_machine_shape} (design-ledger.md §4.2, PD-09): the billed vCPU and memory the ledger prices each machine
 * type at, under the {@code gcp-shared-core-v1} table version. Rows are inserted once and never updated.
 */
@Repository
public class GcpShapeStore {
    /** {@code billedVcpu} is null for a shared-core type missing from the table (GCP_SHARED_CORE_UNSUPPORTED). */
    public record StoredShape(String machineType, String series, BigDecimal billedVcpu, BigDecimal memoryGib,
                              boolean sharedCore, String tableVersion) {
    }

    private final JdbcTemplate jdbc;

    public GcpShapeStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Stores the shape; true when a row was written, false when that type and table version already had one. */
    public boolean insert(StoredShape s, Instant fetchedAt) {
        return jdbc.update("INSERT INTO gcp_machine_shape (machine_type, table_version, series, billed_vcpu, memory_gib,"
                        + " shared_core, fetched_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                s.machineType(), s.tableVersion(), s.series(), s.billedVcpu(), s.memoryGib(), s.sharedCore(),
                Timestamp.from(fetchedAt)) > 0;
    }

    /** The newest shape stored for each of the machine types. */
    public List<StoredShape> forTypes(List<String> machineTypes) {
        if (machineTypes.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", machineTypes.stream().map(t -> "?").toList());
        return jdbc.query("SELECT DISTINCT ON (machine_type) machine_type, series, billed_vcpu, memory_gib, shared_core,"
                        + " table_version FROM gcp_machine_shape WHERE machine_type IN (" + in + ")"
                        + " ORDER BY machine_type, fetched_at DESC, table_version DESC",
                (rs, i) -> new StoredShape(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBoolean(5), rs.getString(6)), machineTypes.toArray());
    }
}
