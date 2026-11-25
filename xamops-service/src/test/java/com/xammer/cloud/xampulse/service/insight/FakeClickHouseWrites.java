package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.service.platform.ClickHouseWrites;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Captures insert calls so tests can assert table, columns and row shape without a real ClickHouse. */
class FakeClickHouseWrites implements ClickHouseWrites {

    record Call(String table, List<String> columns, List<List<Object>> rows) {}

    final List<Call> calls = new ArrayList<>();
    SQLException toThrow;

    @Override
    public void insert(String table, List<String> columns, List<List<Object>> rows) throws SQLException {
        if (toThrow != null) {
            throw toThrow;
        }
        calls.add(new Call(table, List.copyOf(columns), new ArrayList<>(rows)));
    }
}
