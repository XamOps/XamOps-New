package com.xammer.cloud.autospotting.ledger.prices;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.*;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceEligibility;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceEligibility.Snapshot;
import com.xammer.cloud.domain.autospotting.GroupState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** design-ledger.md §4.4, AC-LED-18. */
class PriceEligibilityTest {
    private static final Instant DAY = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = DAY.plusSeconds(86_400);

    private static Snapshot snap(String id, Instant from, Instant to, boolean stated, Instant fetched) {
        return new Snapshot(id, new BigDecimal("0.0570000000"), from, to, stated, fetched);
    }

    @Test
    void statedPriceCoveringTheWindowMayPriceIt() {
        assertThat(PriceEligibility.mayPrice(snap("a", DAY.minusSeconds(60), null, true, END), DAY, END)).isTrue();
        assertThat(PriceEligibility.mayPrice(snap("a", DAY.minusSeconds(60), END, true, END), DAY, END)).isTrue();
    }

    @Test
    void priceThatEndsBeforeTheWindowEndMustBeSplit() {
        assertThat(PriceEligibility.mayPrice(snap("a", DAY.minusSeconds(60), END.minusSeconds(1), true, END), DAY, END)).isFalse();
    }

    @Test
    void priceWithNoStatedTimeFetchedLaterCannotPriceThePast() {
        Snapshot s = snap("a", DAY.minusSeconds(3600), null, false, END.plusSeconds(10));
        assertThat(PriceEligibility.mayPrice(s, DAY, END)).isFalse();
        assertThat(PriceEligibility.usableFrom(s)).isEqualTo(END.plusSeconds(10));
    }

    @Test
    void priceWithNoStatedTimeFetchedBeforeTheWindowMayPriceIt() {
        assertThat(PriceEligibility.mayPrice(snap("a", DAY.minusSeconds(3600), null, false, DAY.minusSeconds(60)), DAY, END)).isTrue();
    }

    @Test
    void seriesClosesEachSnapshotAtTheNextOnesStart() {
        List<Snapshot> out = PriceEligibility.forSeries(List.of(
                snap("b", DAY.plusSeconds(100), null, true, DAY),
                snap("a", DAY, null, true, DAY)));
        assertThat(out).extracting(Snapshot::id).containsExactly("a", "b");
        assertThat(out.get(0).effectiveTo()).isEqualTo(DAY.plusSeconds(100));
        assertThat(out.get(1).effectiveTo()).isNull();
    }

    /** The §4.4 test: a past window with only a later-fetched, no-stated-time snapshot is UNKNOWN(MISSING_SPOT_PRICE). */
    @Test
    void pastWindowWithOnlyALaterFetchedUnstatedSnapshotIsMissingSpotPrice() {
        Snapshot late = snap("s1", DAY.minusSeconds(3600), null, false, END.plusSeconds(5));
        List<CalcInputs.SpotPrice> prices = PriceEligibility.forSeries(List.of(late)).stream()
                .map(s -> new SpotPrice(s.id(), "m5.xlarge", "us-east-1a", "LINUX", s.rate(), s.effectiveFrom(), s.effectiveTo()))
                .toList();
        CalcInputs in = new CalcInputs("aws-1", "AWS", "USD", DAY, END, new Group("g1", 1L, 2L, "us-east-1"),
                DAY.minusSeconds(86_400), new Contract("c1", new BigDecimal("20")), FeeBasis.CONTRACT, Tier.MEASURED_ONLY,
                List.of(new Transition(1, GroupState.PLANNED, GroupState.ACTIVE, DAY.minusSeconds(7200), DAY.minusSeconds(7200))),
                List.of(),
                List.of(new Interval("i1", "i-1", "m5.xlarge", "us-east-1a", "LINUX", PurchaseOption.SPOT, BigDecimal.ONE,
                        DAY.minusSeconds(3600), null, null, null, null)),
                List.of(new CapacitySample(DAY.minusSeconds(3600), new BigDecimal("1"))), prices,
                List.of(new OdPrice("od1", "m5.xlarge", "LINUX", new BigDecimal("0.192"), new BigDecimal("0.192"), DiscountBasis.NONE)),
                new Original(BigDecimal.ZERO, new BigDecimal("100"), List.of(new PreType("m5.xlarge", null, BigDecimal.ONE, null))),
                CommitmentsStatus.UNAVAILABLE, List.of());
        var entry = new SavingsCalculator().calculate(in).orElseThrow();
        assertThat(entry.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(entry.unknownInputs()).contains("MISSING_SPOT_PRICE");
        assertThat(entry.billable()).isFalse();
        assertThat(entry.netSavings()).isEqualByComparingTo("0");
    }
}
