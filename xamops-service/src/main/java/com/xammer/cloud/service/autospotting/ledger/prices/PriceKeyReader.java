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

    /** One GCP pool to price: the group's project location (zone or region) and a machine type in use. */
    public record GcpKey(long cloudAccountId, String region, String zone, String machineType) {
    }

    private static final String GCP_SQL = "SELECT DISTINCT g.cloud_account_id, g.group_key, i.zone, i.instance_type"
            + " FROM spot_usage_interval i JOIN spot_group g ON g.id = i.group_id"
            + " WHERE g.provider = 'GCP' AND g.state NOT IN ('UNMANAGED', 'PLANNED', 'REVERTED', 'GONE', 'FAILED')"
            + " AND i.provider = 'GCP' AND (i.closed = false OR i.end_at >= now() - interval '2 days')"
            + " ORDER BY 1, 2, 3, 4";

    /** The pools of managed GCP groups; a group key is {@code project/location/name}, the region is the location's. */
    public List<GcpKey> readGcp() {
        List<GcpKey> out = new java.util.ArrayList<>();
        jdbc.query(GCP_SQL, rs -> {
            String[] parts = rs.getString(2).split("/", -1);
            if (parts.length == 3 && rs.getString(3) != null) {
                out.add(new GcpKey(rs.getLong(1), regionOf(parts[1]), rs.getString(3), rs.getString(4)));
            }
        });
        return out;
    }

    /** {@code us-central1-a} is in {@code us-central1}; a regional MIG location is already the region. */
    public static String regionOf(String location) {
        return location.matches(".*-[a-z]$") ? location.substring(0, location.lastIndexOf('-')) : location;
    }
}
