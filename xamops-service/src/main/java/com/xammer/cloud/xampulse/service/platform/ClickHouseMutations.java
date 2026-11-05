package com.xammer.cloud.xampulse.service.platform;

import java.sql.SQLException;
import java.util.List;

/** Writer-side parameterised mutations (ALTER ... DELETE/UPDATE), replaceable in tests. */
public interface ClickHouseMutations {
    void execute(String sql, List<Object> params) throws SQLException;
}
