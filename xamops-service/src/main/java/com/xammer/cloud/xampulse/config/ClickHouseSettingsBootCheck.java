package com.xammer.cloud.xampulse.config;

import com.xammer.cloud.xampulse.service.query.ClickHouseGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boot check that the ClickHouse server accepts the per-query {@code SQL_xp_tenant} custom setting
 * ({@code custom_settings_prefixes=SQL_xp_}). Without it every tenant-scoped read would match no row policy and
 * return nothing, so a missing prefix fails startup loudly. The check is functional (can the reader use the
 * setting?) so it passes whether the server config says {@code SQL_} or {@code SQL_xp_}.
 *
 * <p>If ClickHouse is unreachable, boot continues (the pools already tolerate that), a WARN is logged and the check
 * is retried every five minutes until it has passed once.
 */
@Component("xpClickHouseSettingsBootCheck")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ClickHouseSettingsBootCheck {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseSettingsBootCheck.class);

    static final String PROBE_SQL = "SELECT getSetting('SQL_xp_tenant') AS v SETTINGS SQL_xp_tenant = '0'";

    enum Outcome { MISCONFIGURED, UNREACHABLE }

    private final ClickHouseGateway gateway;
    private final AtomicBoolean passed = new AtomicBoolean(false);

    public ClickHouseSettingsBootCheck(ClickHouseGateway gateway) {
        this.gateway = gateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        check(); // logs when unreachable; throws when misconfigured
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void retryUntilPassed() {
        if (!passed.get()) {
            try {
                check();
            } catch (IllegalStateException e) {
                log.error("{}", e.getMessage());
            }
        }
    }

    /** @return true when the server accepted the setting; false when ClickHouse could not be reached. */
    boolean check() {
        try {
            gateway.query(PROBE_SQL, List.of(), 5);
            passed.set(true);
            return true;
        } catch (SQLException e) {
            if (classify(e) == Outcome.MISCONFIGURED) {
                throw new IllegalStateException("ClickHouse rejected the SQL_xp_tenant setting. Set "
                        + "custom_settings_prefixes=SQL_xp_ in the ClickHouse server config (deploy/xampulse/clickhouse/"
                        + "config.d/xampulse.xml); without it every XamPulse read returns no rows.");
            }
            log.warn("XamPulse ClickHouse settings check could not reach ClickHouse ({}); will retry",
                    e.getClass().getSimpleName());
            return false;
        } catch (RuntimeException e) {
            log.warn("XamPulse ClickHouse settings check failed to run ({}); will retry", e.getClass().getSimpleName());
            return false;
        }
    }

    /** "Unknown setting" (code 115) means the prefix is missing; anything else is treated as ClickHouse being down. */
    static Outcome classify(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se && se.getErrorCode() == 115) {
                return Outcome.MISCONFIGURED;
            }
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (m.contains("unknown setting") || m.contains("code: 115") || m.contains("code=115")) {
                return Outcome.MISCONFIGURED;
            }
        }
        return Outcome.UNREACHABLE;
    }
}
