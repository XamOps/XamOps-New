package com.xammer.cloud.xampulse.service.platform;

import java.sql.SQLException;
import java.util.Map;

/** Writer-side DDL seam used by the migration runner, replaceable in tests. */
public interface ClickHouseDdl {

    void execute(String sql) throws SQLException;

    /** Version to checksum, as recorded in {@code xp_schema_version}. */
    Map<String, String> applied() throws SQLException;

    void markApplied(String version, String checksum) throws SQLException;
}
