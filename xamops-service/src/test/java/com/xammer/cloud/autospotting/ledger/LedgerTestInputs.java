package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Test builder for {@link CalcInputs}: a steady AWS group (desired 4, one On-Demand floor, three Spot
 * {@code m5.xlarge}, list $0.192, Spot $0.057, active since September 15, contract in force, bill verified)
 * that a test changes one piece of at a time.
 */
final class LedgerTestInputs {
    static final String GROUP = "11111111-1111-4111-8111-111111111111";
    static final String CONTRACT = "cccccccc-0000-4000-8000-000000000001";
    static final String SPOT_SNAP = "50000000-0000-4000-8000-000000000001";
    static final String OD_SNAP = "60000000-0000-4000-8000-000000000001";

    String date = "2026-10-01";
    Instant goLive = Instant.parse("2026-09-01T00:00:00Z");
    Contract contract = new Contract(CONTRACT, bd("20.000000"));
    FeeBasis feeBasis = FeeBasis.CONTRACT;
    Tier tier = Tier.BILL_VERIFIED;
    List<Transition> transitions = new ArrayList<>(List.of(
            new Transition(1, GroupState.PLANNED, GroupState.ACTIVE, at("2026-09-15T00:00:00Z"), at("2026-09-15T00:00:00Z"))));
    List<RevertOp> revertOps = new ArrayList<>();
    List<Interval> intervals = new ArrayList<>();
    List<CapacitySample> samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("4"))));
    List<SpotPrice> spotPrices = new ArrayList<>(List.of(spot("m5.xlarge", "0.0570000000", "Linux")));
    List<OdPrice> odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000",
            DiscountBasis.PROVEN_NONE, "Linux")));
    Original original = new Original(bd("0"), bd("100"), List.of(pre("m5.xlarge", "Linux", "1", "8")));
    CommitmentsStatus commitmentsStatus = CommitmentsStatus.NONE_PROVEN;
    List<Commitment> commitments = new ArrayList<>();

    private LedgerTestInputs() {}

    /** The steady group for one day: an On-Demand floor and three Spot instances running the whole day. */
    static LedgerTestInputs steady(String date) {
        LedgerTestInputs b = new LedgerTestInputs();
        b.date = date;
        b.intervals.add(od("od-floor-1", "m5.xlarge", "2026-09-15T00:00:00Z", null));
        for (int k = 1; k <= 3; k++) {
            b.intervals.add(spotInstance("spot-" + k, "m5.xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED));
        }
        return b;
    }

    CalcInputs build() {
        Instant start = Instant.parse(date + "T00:00:00Z");
        Instant end = start.plusSeconds(86_400L);
        return new CalcInputs("aws-1", "AWS", "USD", start, end, new Group(GROUP, 1001L, 501L, "us-east-1"), goLive,
                contract, feeBasis, tier, transitions, revertOps, intervals, samples, spotPrices, odPrices, original,
                commitmentsStatus, commitments);
    }

    LedgerTestInputs date(String d) {
        this.date = d;
        return this;
    }

    // ---- element factories

    static Instant at(String iso) {
        return Instant.parse(iso);
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** A time on the builder's day. */
    Instant t(String hms) {
        return Instant.parse(date + "T" + hms + "Z");
    }

    static Interval od(String id, String type, String start, String end) {
        return new Interval(id, "i-" + id, type, "us-east-1a", "Linux", PurchaseOption.ON_DEMAND, bd("1"),
                at(start), end == null ? null : at(end), null, null, end == null ? null : EndCause.TERMINATED);
    }

    static Interval spotInstance(String id, String type, String start, String end, EndCause cause) {
        return new Interval(id, "i-" + id, type, "us-east-1a", "Linux", PurchaseOption.SPOT, bd("1"),
                at(start), end == null ? null : at(end), null, null, end == null ? null : cause);
    }

    static Interval weighted(Interval i, String weight) {
        return new Interval(i.intervalId(), i.instanceId(), i.instanceType(), i.zone(), i.platform(),
                i.purchaseOption(), bd(weight), i.startAt(), i.endAt(), i.lastSeenAt(), i.firstAbsentAt(), i.endCause());
    }

    static Interval platform(Interval i, String platform) {
        return new Interval(i.intervalId(), i.instanceId(), i.instanceType(), i.zone(), platform,
                i.purchaseOption(), i.capacityWeight(), i.startAt(), i.endAt(), i.lastSeenAt(), i.firstAbsentAt(),
                i.endCause());
    }

    static Interval unknownEnd(Interval i, String lastSeen, String firstAbsent) {
        return new Interval(i.intervalId(), i.instanceId(), i.instanceType(), i.zone(), i.platform(),
                i.purchaseOption(), i.capacityWeight(), i.startAt(), null, at(lastSeen), at(firstAbsent), null);
    }

    static SpotPrice spot(String type, String rate, String platform) {
        return new SpotPrice(SPOT_SNAP, type, "us-east-1a", platform, bd(rate), at("2026-08-01T00:00:00Z"), null);
    }

    static OdPrice od(String type, String list, String effective, DiscountBasis basis, String platform) {
        return new OdPrice(OD_SNAP, type, platform, bd(list), bd(effective), basis);
    }

    static PreType pre(String type, String platform, String weight, String norm) {
        return new PreType(type, platform, bd(weight), norm == null ? null : bd(norm));
    }

    static Transition transition(long id, GroupState from, GroupState to, String effective) {
        return new Transition(id, from, to, at(effective), at(effective));
    }

    static LocalDate month(String iso) {
        return LocalDate.parse(iso);
    }
}
