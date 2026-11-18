package com.xammer.cloud.service.autospotting.ledger.prices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

/**
 * The (account, region, zone, type, platform) keys the price-snapshot job must price: those of the
 * instance intervals of AWS groups that are not in a terminal or unmanaged state (design-ledger.md §4.1
 * Schedule), including intervals that ended in the last two days so a late price still lands. A group
 * with no interval yet has no key: nothing is priced until it has run an instance.
 */
@Repository
public class PriceKeyReader {

    /** One pool to price. {@code platform} is a Platform name (LINUX, WINDOWS, ...), null when unknown. */
    public record Key(long cloudAccountId, String region, String zone, String instanceType, String platform) {
    }

    private static final String SQL = "SELECT DISTINCT g.cloud_account_id, split_part(g.group_key, '/', 1) AS region,"
            + " i.zone, i.instance_type, i.platform"
            + " FROM spot_usage_interval i JOIN spot_group g ON g.id = i.group_id"
            + " WHERE g.provider = 'AWS' AND g.state NOT IN ('UNMANAGED', 'PLANNED', 'REVERTED', 'GONE', 'FAILED')"
            + " AND i.provider = 'AWS' AND (i.closed = false OR i.end_at >= now() - interval '2 days')"
            + " ORDER BY 1, 2, 3, 4, 5";

    private final JdbcTemplate jdbc;

    public PriceKeyReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<Key> read() {
        return jdbc.query(SQL, (rs, i) -> new Key(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)));
    }
}
