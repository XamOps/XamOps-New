package com.xammer.cloud.xampulse.service.platform;

import java.sql.SQLException;
import java.util.List;

/**
 * Writer-side parameterised batch INSERT, replaceable in tests. The implementation validates the table and every
 * column against a closed per-table whitelist, so no identifier is ever derived from user text.
 */
public interface ClickHouseWrites {
    void insert(String table, List<String> columns, List<List<Object>> rows) throws SQLException;
}
