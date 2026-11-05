package com.xammer.cloud.xampulse.config;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseSettingsBootCheckTest {

    @Test
    void unknownSettingMeansTheCustomPrefixIsMissing() {
        assertEquals(ClickHouseSettingsBootCheck.Outcome.MISCONFIGURED,
                ClickHouseSettingsBootCheck.classify(new SQLException("x", "HY000", 115)));
        assertEquals(ClickHouseSettingsBootCheck.Outcome.MISCONFIGURED,
                ClickHouseSettingsBootCheck.classify(new SQLException("Code: 115. DB::Exception: Unknown setting SQL_xp_tenant")));
        assertEquals(ClickHouseSettingsBootCheck.Outcome.MISCONFIGURED,
                ClickHouseSettingsBootCheck.classify(new SQLException("wrapped", new RuntimeException("Unknown setting 'SQL_xp_tenant'"))));
    }

    @Test
    void connectionProblemsAreNotAMisconfiguration() {
        assertEquals(ClickHouseSettingsBootCheck.Outcome.UNREACHABLE,
                ClickHouseSettingsBootCheck.classify(new SQLException("Connection refused")));
    }

    @Test
    void passesWhenTheServerAcceptsTheSetting() {
        var check = new ClickHouseSettingsBootCheck((sql, params, t) -> {
            assertTrue(sql.contains("SQL_xp_tenant"));
            return List.of();
        });
        assertTrue(check.check());
    }

    @Test
    void failsBootWhenThePrefixIsMissing() {
        var check = new ClickHouseSettingsBootCheck((sql, params, t) -> {
            throw new SQLException("Code: 115. Unknown setting");
        });
        IllegalStateException e = assertThrows(IllegalStateException.class, check::check);
        assertTrue(e.getMessage().contains("custom_settings_prefixes=SQL_xp_"));
    }

    @Test
    void doesNotFailBootWhenClickHouseIsDown() {
        var check = new ClickHouseSettingsBootCheck((sql, params, t) -> {
            throw new SQLException("Connection refused");
        });
        assertFalse(check.check());
        assertDoesNotThrow(check::retryUntilPassed);
    }
}
