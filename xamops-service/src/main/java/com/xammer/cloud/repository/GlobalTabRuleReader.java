package com.xammer.cloud.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the platform-wide XamOps page rules ({@code global_tab_rule} / {@code global_tab_rule_exception},
 * migration V20261102_1). BillOps owns the writes from its admin User Manager; this side only needs
 * "which rules apply to this user", so it is plain JDBC rather than a second set of entities.
 *
 * <p>Every query first checks the table exists. A database without the tables (a local run where
 * Flyway is off and BillOps has not created them yet) then behaves exactly as before: no rules.
 * Checking up front, rather than catching the SQL error, matters because a failed statement aborts
 * the surrounding PostgreSQL transaction.
 */
@Repository
public class GlobalTabRuleReader {

    private static final Logger log = LoggerFactory.getLogger(GlobalTabRuleReader.class);

    /** Rules applying to one user: tab key + mode, minus the rules that list the user as an exception. */
    private static final String RULES_FOR_USER_SQL = """
            SELECT r.tab_key, r.mode
            FROM global_tab_rule r
            WHERE NOT EXISTS (
                SELECT 1 FROM global_tab_rule_exception e
                WHERE e.rule_id = r.id AND e.user_id = ?
            )
            """;

    private final JdbcTemplate jdbc;

    /** Set once the tables are seen; they are never dropped at runtime. */
    private volatile boolean tablesPresent;

    /** @param dataSource the {@code @Primary} application DataSource */
    public GlobalTabRuleReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Hidden and granted keys for a user. Both lists are empty when no rule applies. */
    public record UserRules(List<String> hidden, List<String> granted) {
        public static final UserRules NONE = new UserRules(List.of(), List.of());
    }

    public UserRules rulesFor(Long userId) {
        if (userId == null || !tablesPresent()) {
            return UserRules.NONE;
        }
        List<String> hidden = new ArrayList<>();
        List<String> granted = new ArrayList<>();
        jdbc.query(RULES_FOR_USER_SQL, rs -> {
            String key = rs.getString("tab_key");
            if ("HIDE".equals(rs.getString("mode"))) {
                hidden.add(key);
            } else if ("GRANT".equals(rs.getString("mode"))) {
                granted.add(key);
            }
        }, userId);
        return new UserRules(hidden, granted);
    }

    /** Releases a user's exception rows so the user itself can be deleted. */
    public void deleteExceptionsForUser(Long userId) {
        if (userId == null || !tablesPresent()) {
            return;
        }
        jdbc.update("DELETE FROM global_tab_rule_exception WHERE user_id = ?", userId);
    }

    private boolean tablesPresent() {
        if (tablesPresent) {
            return true;
        }
        Boolean present = jdbc.queryForObject(
                "SELECT to_regclass('global_tab_rule') IS NOT NULL AND to_regclass('global_tab_rule_exception') IS NOT NULL",
                Boolean.class);
        if (Boolean.TRUE.equals(present)) {
            tablesPresent = true;
        } else {
            log.debug("global_tab_rule tables not present yet; no platform-wide page rules applied");
        }
        return tablesPresent;
    }
}
