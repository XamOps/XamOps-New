package com.xammer.cloud.xampulse.service.query;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Seam over the JDBC connection so tenant scoping, timeouts and error mapping are unit-testable without a
 * ClickHouse server. The production implementation is {@link JdbcClickHouseGateway}.
 */
public interface ClickHouseGateway {

    /** Runs a parameterised SELECT. {@code sql} already carries the settings clause appended by the executor. */
    List<Map<String, Object>> query(String sql, List<Object> params, int timeoutSec) throws SQLException;
}
