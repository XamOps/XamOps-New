package com.xammer.cloud.service.autospotting.ledger.prices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inserts {@code spot_price_snapshot} rows (design-ledger.md §2.2). The table is append-only (trigger),
 * and {@code ux_sps_dedupe} makes a repeated fetch a no-op: a row already stored for the same source,
 * kind, region, zone, type, platform, {@code effective_from} and payload hash is skipped, never updated.
 */
@Repository
public class PriceSnapshotStore {

    /** A snapshot to store. {@code zone} may be null; {@code effectiveTo} is null unless the source states an end. */
    public record NewSnapshot(String source, String priceKind, String region, String zone, String skuOrType,
                              String platform, BigDecimal rate, String unit, String currency, Instant effectiveFrom,
                              Instant effectiveTo, String sourceVersion, boolean sourceEffectiveTimeStated,
                              Instant fetchedAt, String payloadSha256, String payloadJson) {
    }

    private final JdbcTemplate jdbc;

    public PriceSnapshotStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Stores the snapshot; returns true when a row was written, false when the dedupe index already had it. */
    public boolean insert(NewSnapshot s) {
        int rows = jdbc.update("INSERT INTO spot_price_snapshot (id, provider, source, price_kind, region, zone,"
                        + " sku_or_type, platform, rate, unit, currency, effective_from, effective_to, source_version,"
                        + " source_effective_time_stated, fetched_at, payload_sha256, payload)"
                        + " VALUES (?, 'AWS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))"
                        + " ON CONFLICT DO NOTHING",
                UUID.randomUUID(), s.source(), s.priceKind(), s.region(), s.zone(), s.skuOrType(), s.platform(),
                s.rate(), s.unit(), s.currency(), Timestamp.from(s.effectiveFrom()),
                s.effectiveTo() == null ? null : Timestamp.from(s.effectiveTo()), s.sourceVersion(),
                s.sourceEffectiveTimeStated(), Timestamp.from(s.fetchedAt()), s.payloadSha256(), s.payloadJson());
        return rows > 0;
    }

    /** The newest {@code effective_from} stored for a Spot pool, so the next fetch starts there. */
    public Optional<Instant> latestSpotFrom(String region, String zone, String instanceType, String platform) {
        List<Timestamp> rows = jdbc.query("SELECT max(effective_from) FROM spot_price_snapshot WHERE provider = 'AWS'"
                        + " AND source = 'AWS_SPOT_HISTORY' AND price_kind = 'SPOT' AND region = ? AND zone = ?"
                        + " AND sku_or_type = ? AND platform = ?",
                (rs, i) -> rs.getTimestamp(1), region, zone, instanceType, platform);
        return rows.stream().filter(java.util.Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    /** True when a list price for the key was already fetched at or after {@code since} (the daily list fetch). */
    public boolean listPriceFetchedSince(String region, String instanceType, String platform, Instant since) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM spot_price_snapshot WHERE provider = 'AWS'"
                        + " AND source = 'AWS_PRICE_LIST' AND price_kind = 'ON_DEMAND' AND region = ?"
                        + " AND sku_or_type = ? AND platform = ? AND fetched_at >= ?",
                Integer.class, region, instanceType, platform, Timestamp.from(since));
        return n != null && n > 0;
    }
}
