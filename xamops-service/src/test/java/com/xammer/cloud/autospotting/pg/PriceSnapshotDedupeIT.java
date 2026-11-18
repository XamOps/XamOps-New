package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.ledger.prices.PriceSnapshotStore;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceSnapshotStore.NewSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** ux_sps_dedupe (design-ledger.md §2.2): the same fetched price is stored once, a changed one is a new row. */
@Tag("containers")
class PriceSnapshotDedupeIT {

    private static NewSnapshot snap(String rate, String sha, Instant from) {
        return new NewSnapshot("AWS_SPOT_HISTORY", "SPOT", "us-east-1", "7/us-east-1a", "m5.xlarge", "LINUX",
                new BigDecimal(rate), "HOUR", "USD", from, null, "ec2-spot-price-history", true,
                Instant.parse("2026-10-05T10:00:00Z"), sha, "{\"spotPrice\":\"" + rate + "\"}");
    }

    @Test
    void sameSnapshotTwiceIsStoredOnce_andRateKeepsAllTenPlaces() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            PriceSnapshotStore store = new PriceSnapshotStore(Ledger17Support.dataSource(db));
            Instant from = Instant.parse("2026-10-05T09:00:00Z");
            assertThat(store.insert(snap("0.0570000000", "a".repeat(64), from))).isTrue();
            assertThat(store.insert(snap("0.0570000000", "a".repeat(64), from))).isFalse();
            assertThat(store.insert(snap("0.0580000000", "b".repeat(64), from.plusSeconds(60)))).isTrue();
            assertThat(db.jdbc().queryForObject("SELECT count(*) FROM spot_price_snapshot", Integer.class)).isEqualTo(2);
            assertThat(db.jdbc().queryForObject("SELECT rate::text FROM spot_price_snapshot WHERE payload_sha256 = ?",
                    String.class, "a".repeat(64))).isEqualTo("0.057000000000");
            assertThat(store.latestSpotFrom("us-east-1", "7/us-east-1a", "m5.xlarge", "LINUX")).contains(from.plusSeconds(60));
        }
    }
}
