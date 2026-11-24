package com.xammer.cloud.service.autospotting.ledger.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CapacitySample;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentsStatus;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Contract;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.DiscountBasis;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.EndCause;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.FeeBasis;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Group;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Interval;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.OdPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Original;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PreType;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PurchaseOption;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.RevertOp;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.SpotPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Transition;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.prices.AwsPriceSnapshotJob;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceEligibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the calculator inputs of a group-day from the working tables (design-ledger.md §13.2 step 3):
 * state transitions, confirmed revert operations, instance intervals, capacity samples, price snapshots,
 * the contract and fee basis, go-live and the pre-enable configuration. Every instant is cut to whole
 * seconds (the {@link CalcInputs} contract), every rate keeps the scale PostgreSQL stored (so the
 * canonical text and hash are stable), and nothing is read from the JVM zone or clock.
 *
 * <p>Inputs this phase cannot establish take the design's safe default, never a guess:
 * <ul>
 *   <li><b>Reconciliation tier</b> is {@code MEASURED_ONLY}: nothing is verified against a bill until
 *       Phase 25, and the calculator only ever lowers it.</li>
 *   <li><b>Commitments</b> are {@code UNAVAILABLE} (utilization is read in Phase 24): the entry is capped
 *       at {@code MEASURED_ONLY}, so it is measured, stored and never billable.</li>
 *   <li><b>Discount</b>: the contract's {@code declared_discount_pct} when declared (effective rate =
 *       list x (1 - pct/100), rounded once at 12 places), else {@code NONE}: the list rate with the
 *       {@code DISCOUNT_NOT_DECLARED} cap (§5.1 order 4). A null is never read as 0.</li>
 *   <li><b>Fee basis</b> comes from the fee basis the confirmed ENABLE or ADOPT plan recorded; an
 *       unreadable one never bills.</li>
 * </ul>
 */
@Repository("awsLedgerInputs")
public class JdbcLedgerInputsSource implements LedgerInputsSource {

    static final List<String> MEASURED_TO_STATES = List.of("ACTIVE", "DEGRADED", "FALLBACK", "PAUSED", "APPLYING",
            "REVERTING", "LEGACY_UNCONFIRMED");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
    private static final int RATE_SCALE = 12;

    protected final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    /** {@code AWS} or {@code GCP}: one source per provider, over the same working tables. */
    protected final String provider;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcLedgerInputsSource(DataSource dataSource) {
        this(dataSource, "AWS");
    }

    protected JdbcLedgerInputsSource(DataSource dataSource, String provider) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.provider = provider;
    }

    private static String measuredList() {
        return MEASURED_TO_STATES.stream().map(s -> "'" + s + "'").collect(Collectors.joining(","));
    }

    // ------------------------------------------------------------------ accounts and days

    @Override
    public List<Long> candidateAccounts() {
        return jdbc.queryForList("SELECT DISTINCT g.cloud_account_id FROM spot_group g WHERE g.provider = '" + provider + "'"
                + " AND (g.state = 'LEGACY_UNCONFIRMED' OR EXISTS (SELECT 1 FROM spot_group_transition t"
                + " WHERE t.group_id = g.id AND t.to_state IN (" + measuredList() + "))) ORDER BY 1", Long.class);
    }

    @Override
    public Optional<LocalDate> firstRelevantDay(long cloudAccountId) {
        List<Timestamp> rows = jdbc.query("SELECT min(d) FROM ("
                        + " SELECT min(t.effective_at) AS d FROM spot_group g JOIN spot_group_transition t"
                        + "   ON t.group_id = g.id WHERE g.provider = '" + provider + "' AND g.cloud_account_id = ?"
                        + "   AND t.to_state IN (" + measuredList() + ") GROUP BY g.id"
                        + " UNION ALL SELECT g.created_at FROM spot_group g WHERE g.provider = '" + provider + "'"
                        + "   AND g.cloud_account_id = ? AND g.state = 'LEGACY_UNCONFIRMED') x",
                (rs, i) -> rs.getTimestamp(1), cloudAccountId, cloudAccountId);
        return rows.stream().filter(java.util.Objects::nonNull).findFirst()
                .map(t -> t.toInstant().atZone(ZoneOffset.UTC).toLocalDate());
    }

    // ------------------------------------------------------------------ inputs

    protected record GroupRow(UUID id, long clientId, String groupKey, String state, Instant createdAt,
                            String snapshotPayload) {
    }

    @Override
    public List<CalcInputs> inputsFor(long cloudAccountId, LocalDate day) {
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        LocalDate month = day.withDayOfMonth(1);
        Instant monthStart = month.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant goLive = jdbc.query("SELECT go_live_at FROM savings_ledger_golive WHERE provider = '" + provider + "'",
                (rs, i) -> rs.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.SECONDS)).stream().findFirst()
                .orElse(null);

        List<GroupRow> groups = jdbc.query("SELECT g.id, g.client_id, g.group_key, g.state, g.created_at,"
                        + " CAST(s.payload AS text) FROM spot_group g LEFT JOIN spot_group_snapshot s"
                        + " ON s.id = g.snapshot_id WHERE g.provider = '" + provider + "' AND g.cloud_account_id = ?"
                        + " AND (g.state = 'LEGACY_UNCONFIRMED' OR EXISTS (SELECT 1 FROM spot_group_transition t"
                        + " WHERE t.group_id = g.id AND t.to_state IN (" + measuredList() + ")))"
                        + " ORDER BY g.id",
                (rs, i) -> new GroupRow(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(6)), cloudAccountId);

        List<CalcInputs> out = new ArrayList<>();
        for (GroupRow g : groups) {
            out.add(inputsOf(cloudAccountId, g, start, end, monthStart, goLive));
        }
        return out;
    }

    /** The region of a group key: AWS keys start with it ({@code region/asg}); the GCP source overrides this. */
    protected String regionOf(String groupKey) {
        return groupKey.contains("/") ? groupKey.substring(0, groupKey.indexOf('/')) : null;
    }

    /** The pre-enable configuration of the group; the GCP source overrides this (templates, not an ASG). */
    protected Original originalOf(String snapshotPayload, JsonNode plan) {
        return original(snapshotPayload, plan);
    }

    private CalcInputs inputsOf(long accountId, GroupRow g, Instant start, Instant end, Instant monthStart,
                                Instant goLive) {
        String region = regionOf(g.groupKey());

        List<Transition> transitions = jdbc.query("SELECT id, from_state, to_state, effective_at, occurred_at"
                        + " FROM spot_group_transition WHERE group_id = ? AND effective_at < ? ORDER BY id",
                (rs, i) -> new Transition(rs.getLong(1), state(rs.getString(2)), state(rs.getString(3)),
                        sec(rs.getTimestamp(4)), sec(rs.getTimestamp(5))), g.id(), Timestamp.from(end));

        List<RevertOp> revertOps = jdbc.query("SELECT o.id, p.confirmed_at, o.finished_at, o.first_mutation_seq"
                        + " FROM spot_operation o JOIN spot_plan p ON p.id = o.plan_id WHERE o.group_id = ?"
                        + " AND o.kind = 'REVERT' AND o.dry_run = false AND p.dry_run = false"
                        + " AND p.confirmed_at IS NOT NULL AND p.confirmed_at < ?"
                        + " AND (o.finished_at IS NULL OR o.finished_at > ?) ORDER BY p.confirmed_at, o.id",
                (rs, i) -> new RevertOp(rs.getObject(1, UUID.class).toString(), sec(rs.getTimestamp(2)),
                        sec(rs.getTimestamp(3)), rs.getObject(4) == null ? null : rs.getLong(4)),
                g.id(), Timestamp.from(end), Timestamp.from(start));

        List<Interval> intervals = jdbc.query("SELECT id, instance_id, instance_type, zone, platform, purchase_option,"
                        + " capacity_weight, start_at, end_at, last_seen_at, first_absent_at, end_cause"
                        + " FROM spot_usage_interval WHERE group_id = ? AND start_at < ?"
                        + " AND (closed = false OR coalesce(end_at, first_absent_at, last_seen_at, start_at) > ?)"
                        + " ORDER BY start_at, id",
                (rs, i) -> new Interval(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), "SPOT".equals(rs.getString(6)) ? PurchaseOption.SPOT
                        : PurchaseOption.ON_DEMAND, rs.getBigDecimal(7), sec(rs.getTimestamp(8)),
                        sec(rs.getTimestamp(9)), sec(rs.getTimestamp(10)), sec(rs.getTimestamp(11)),
                        endCause(rs.getString(12))), g.id(), Timestamp.from(end), Timestamp.from(start))
                .stream().map(iv -> normalize(iv, start, end)).toList();

        List<CapacitySample> samples = new ArrayList<>(jdbc.query("SELECT observed_at, desired_units"
                        + " FROM spot_group_capacity_sample WHERE group_id = ? AND observed_at < ?"
                        + " AND observed_at >= coalesce((SELECT max(observed_at) FROM spot_group_capacity_sample"
                        + "   WHERE group_id = ? AND observed_at <= ?), ?) ORDER BY observed_at",
                (rs, i) -> new CapacitySample(sec(rs.getTimestamp(1)), rs.getBigDecimal(2)), g.id(),
                Timestamp.from(end), g.id(), Timestamp.from(start), Timestamp.from(start)));

        JsonNode plan = confirmedStartPlan(g.id(), end);
        Original original = originalOf(g.snapshotPayload(), plan);
        Contract contract = null;
        BigDecimal declaredDiscount = null;
        List<java.util.Map<String, Object>> contracts = jdbc.queryForList(
                "SELECT id, share_pct, declared_discount_pct FROM autospotting_contract WHERE client_id = ?"
                        + " AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)",
                g.clientId(), Timestamp.from(monthStart), Timestamp.from(monthStart));
        if (!contracts.isEmpty()) {
            var c = contracts.get(0);
            contract = new Contract(String.valueOf(c.get("id")), (BigDecimal) c.get("share_pct"));
            declaredDiscount = (BigDecimal) c.get("declared_discount_pct");
        }
        FeeBasis feeBasis = feeBasis(plan);
        return assemble(new Parts(accountId, g, region, start, end, goLive, contract, declaredDiscount, feeBasis,
                transitions, revertOps, intervals, samples, original, plan));
    }

    /** Everything read for one group-day that does not depend on the provider's prices. */
    protected record Parts(long accountId, GroupRow g, String region, Instant start, Instant end, Instant goLive,
                           Contract contract, BigDecimal declaredDiscount, FeeBasis feeBasis,
                           List<Transition> transitions, List<RevertOp> revertOps, List<Interval> intervals,
                           List<CapacitySample> samples, Original original, JsonNode plan) {
    }

    /** The AWS prices and the {@code aws-1} inputs; the GCP source overrides this with its own. */
    protected CalcInputs assemble(Parts p) {
        List<SpotPrice> spotPrices = spotPrices(p.accountId(), p.region(), p.intervals(), p.start(), p.end());
        List<OdPrice> odPrices = odPrices(p.region(), p.intervals(), p.original(), p.start(), p.declaredDiscount());
        return new CalcInputs(SavingsCalculator.VERSION, SavingsCalculator.PROVIDER, "USD", p.start(), p.end(),
                new Group(p.g().id().toString(), p.accountId(), p.g().clientId(), p.region()), p.goLive(),
                p.contract(), p.feeBasis(), Tier.MEASURED_ONLY, p.transitions(), p.revertOps(), p.intervals(),
                p.samples(), spotPrices, odPrices, p.original(), CommitmentsStatus.UNAVAILABLE, List.of());
    }

    // ------------------------------------------------------------------ plan, fee basis, original

    private JsonNode confirmedStartPlan(UUID groupId, Instant end) {
        List<String> bodies = jdbc.query("SELECT CAST(body AS text) FROM spot_plan WHERE group_id = ?"
                        + " AND kind IN ('ENABLE', 'ADOPT') AND confirmed_at IS NOT NULL AND dry_run = false"
                        + " AND confirmed_at < ? ORDER BY confirmed_at DESC LIMIT 1",
                (rs, i) -> rs.getString(1), groupId, Timestamp.from(end));
        if (bodies.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(bodies.get(0));
        } catch (IOException e) {
            return null;
        }
    }

    /** The fee basis the plan recorded at confirm (design-core.md §7.3); an unreadable one never bills. */
    static FeeBasis feeBasis(JsonNode plan) {
        if (plan == null) {
            return null;
        }
        JsonNode fee = plan.path("feeBasis");
        if (fee.isMissingNode() || fee.isNull()) {
            return null;
        }
        if (fee.hasNonNull("contractId")) {
            return FeeBasis.CONTRACT;
        }
        return "NO_FEE_ACCOUNT".equals(fee.path("reason").asText(null)) ? FeeBasis.NO_FEE_ACCOUNT
                : FeeBasis.BEFORE_CONTRACT;
    }

    /**
     * The pre-enable configuration (design-ledger.md §3.2, §3.3). A mixed original keeps its base and
     * On-Demand share and prices its override types by weight; a plain launch-template original ran
     * On-Demand only on the template's type, which the confirmed plan recorded as the On-Demand floor type.
     * Null when the snapshot is missing or unreadable: the calculator then reports MISSING_SNAPSHOT.
     */
    static Original original(String snapshotPayload, JsonNode plan) {
        if (snapshotPayload == null) {
            return null;
        }
        try {
            JsonNode owned = new ObjectMapper().readTree(snapshotPayload).path("owned");
            String floorType = plan == null ? null : plan.path("onDemandFloor").path("instanceType").asText(null);
            JsonNode mixed = owned.path("mixedInstancesPolicy");
            List<PreType> pre = new ArrayList<>();
            BigDecimal base = BigDecimal.ZERO;
            BigDecimal pct = HUNDRED;
            if (!mixed.isMissingNode() && !mixed.isNull()) {
                JsonNode dist = mixed.path("instancesDistribution");
                if (dist.hasNonNull("onDemandBaseCapacity")) {
                    base = BigDecimal.valueOf(dist.get("onDemandBaseCapacity").asLong());
                }
                if (dist.hasNonNull("onDemandPercentageAboveBaseCapacity")) {
                    pct = BigDecimal.valueOf(dist.get("onDemandPercentageAboveBaseCapacity").asLong());
                }
                for (JsonNode o : mixed.path("launchTemplate").path("overrides")) {
                    String type = o.path("type").asText(null);
                    if (type == null) {
                        continue;
                    }
                    String w = o.path("weight").asText(null);
                    pre.add(new PreType(type, null, w == null || w.isBlank() ? BigDecimal.ONE : new BigDecimal(w.trim()),
                            null));
                }
            }
            if (pre.isEmpty() && floorType != null) {
                pre.add(new PreType(floorType, null, BigDecimal.ONE, null));
            }
            return new Original(base, pct, pre);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ prices

    private List<SpotPrice> spotPrices(long accountId, String region, List<Interval> intervals, Instant start,
                                       Instant end) {
        if (region == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Interval iv : intervals) {
            if (iv.purchaseOption() == PurchaseOption.SPOT && iv.zone() != null && iv.platform() != null) {
                keys.add(iv.zone() + "|" + iv.instanceType() + "|" + iv.platform());
            }
        }
        List<SpotPrice> out = new ArrayList<>();
        for (String key : keys) {
            String[] k = key.split("\\|", 3);
            String zoneKey = AwsPriceSnapshotJob.zoneKey(accountId, k[0]);
            List<PriceEligibility.Snapshot> rows = new ArrayList<>(jdbc.query("SELECT id, rate, effective_from,"
                            + " effective_to, source_effective_time_stated, fetched_at FROM ("
                            + " (SELECT * FROM spot_price_snapshot WHERE provider = 'AWS' AND price_kind = 'SPOT'"
                            + "   AND region = ? AND zone = ? AND sku_or_type = ? AND platform = ?"
                            + "   AND effective_from >= ? AND effective_from < ?)"
                            + " UNION ALL (SELECT * FROM spot_price_snapshot WHERE provider = 'AWS'"
                            + "   AND price_kind = 'SPOT' AND region = ? AND zone = ? AND sku_or_type = ?"
                            + "   AND platform = ? AND effective_from < ? ORDER BY effective_from DESC LIMIT 3)) s"
                            + " ORDER BY effective_from, id",
                    (rs, i) -> new PriceEligibility.Snapshot(rs.getObject(1, UUID.class).toString(),
                            rs.getBigDecimal(2), sec(rs.getTimestamp(3)), sec(rs.getTimestamp(4)), rs.getBoolean(5),
                            sec(rs.getTimestamp(6))),
                    region, zoneKey, k[1], k[2], Timestamp.from(start), Timestamp.from(end),
                    region, zoneKey, k[1], k[2], Timestamp.from(start)));
            for (PriceEligibility.Snapshot s : PriceEligibility.forSeries(rows)) {
                if (s.effectiveTo() != null && !s.effectiveTo().isAfter(start)) {
                    continue; // ended before the day
                }
                out.add(new SpotPrice(s.id(), k[1], k[0], k[2], s.rate(), s.effectiveFrom(), s.effectiveTo()));
            }
        }
        out.sort(Comparator.comparing(SpotPrice::snapshotId));
        return out;
    }

    private List<OdPrice> odPrices(String region, List<Interval> intervals, Original original, Instant start,
                                   BigDecimal declaredDiscount) {
        if (region == null) {
            return List.of();
        }
        Set<String> platforms = new TreeSet<>();
        Set<String> types = new TreeSet<>();
        for (Interval iv : intervals) {
            if (iv.platform() != null) {
                platforms.add(iv.platform());
                types.add(iv.instanceType());
            }
        }
        if (original != null) {
            for (PreType p : original.preEnable()) {
                types.add(p.instanceType());
            }
        }
        List<OdPrice> out = new ArrayList<>();
        for (String type : types) {
            for (String platform : platforms) {
                // The latest list price that rules 1 and 2 let price the whole day (design-ledger.md §4.4).
                List<PriceEligibility.Snapshot> rows = jdbc.query("SELECT id, rate, effective_from, effective_to,"
                                + " source_effective_time_stated, fetched_at FROM spot_price_snapshot"
                                + " WHERE provider = 'AWS' AND price_kind = 'ON_DEMAND' AND region = ?"
                                + " AND sku_or_type = ? AND platform = ? AND effective_from <= ?"
                                + " ORDER BY effective_from DESC, fetched_at DESC, id DESC LIMIT 5",
                        (rs, i) -> new PriceEligibility.Snapshot(rs.getObject(1, UUID.class).toString(),
                                rs.getBigDecimal(2), sec(rs.getTimestamp(3)), sec(rs.getTimestamp(4)),
                                rs.getBoolean(5), sec(rs.getTimestamp(6))), region, type, platform,
                        Timestamp.from(start));
                Instant dayEnd = start.plus(1, ChronoUnit.DAYS);
                for (PriceEligibility.Snapshot s : rows) {
                    if (PriceEligibility.mayPrice(s, start, dayEnd)) {
                        out.add(od(s, type, platform, declaredDiscount));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** List rate and effective rate; a declared discount is applied once and rounded once at the rate scale. */
    static OdPrice od(PriceEligibility.Snapshot s, String type, String platform, BigDecimal declaredDiscount) {
        BigDecimal list = s.rate();
        if (declaredDiscount == null) {
            return new OdPrice(s.id(), type, platform, list, list, DiscountBasis.NONE);
        }
        BigDecimal effective = list.multiply(HUNDRED.subtract(declaredDiscount)).divide(HUNDRED, RATE_SCALE,
                RoundingMode.HALF_UP);
        return new OdPrice(s.id(), type, platform, list, effective, DiscountBasis.DECLARED);
    }

    // ------------------------------------------------------------------ interval normalization

    /** An end later than this after the start can no longer change a billing rule (first-hour and 60 s rules). */
    static final long RULE_HORIZON_SECONDS = 3600L;

    /**
     * Keeps a day's inputs stable when only facts the day does not depend on change, so a past day is not
     * re-run (and its chain not extended) every time a later sweep moves {@code last_seen_at} or a long
     * instance ends days afterwards:
     * <ul>
     *   <li>an interval that ends, or is last known to be absent, at or after the window end, and that has
     *       lived more than an hour by then, is shown as still running ({@code endAt}, {@code lastSeenAt},
     *       {@code firstAbsentAt}, {@code endCause} cleared). The calculator prices the day identically,
     *       because the first-hour and 60 s billing rules cannot apply to it any more;</li>
     *   <li>{@code lastSeenAt} is kept only where the calculator reads it: an end that is unknown
     *       ({@code endAt} null with a {@code firstAbsentAt}).</li>
     * </ul>
     */
    static Interval normalize(Interval iv, Instant windowStart, Instant windowEnd) {
        Instant knownEnd = iv.endAt() != null ? iv.endAt() : iv.firstAbsentAt();
        if (knownEnd != null && !knownEnd.isBefore(windowEnd) && iv.startAt() != null
                && ChronoUnit.SECONDS.between(iv.startAt(), windowEnd) > RULE_HORIZON_SECONDS) {
            return new Interval(iv.intervalId(), iv.instanceId(), iv.instanceType(), iv.zone(), iv.platform(),
                    iv.purchaseOption(), iv.capacityWeight(), iv.startAt(), null, null, null, null);
        }
        Instant lastSeen = iv.endAt() == null && iv.firstAbsentAt() != null ? iv.lastSeenAt() : null;
        Instant firstAbsent = iv.endAt() == null ? iv.firstAbsentAt() : null;
        return new Interval(iv.intervalId(), iv.instanceId(), iv.instanceType(), iv.zone(), iv.platform(),
                iv.purchaseOption(), iv.capacityWeight(), iv.startAt(), iv.endAt(), lastSeen, firstAbsent,
                iv.endCause());
    }

    // ------------------------------------------------------------------ small mappers

    private static Instant sec(Timestamp t) {
        return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static GroupState state(String s) {
        return s == null ? null : GroupState.valueOf(s);
    }

    /** SCALE_IN, REPLACED and USER are all terminations the provider charged for normally. */
    static EndCause endCause(String stored) {
        if (stored == null) {
            return null;
        }
        return switch (stored) {
            case "PROVIDER_INTERRUPTED" -> EndCause.PROVIDER_INTERRUPTED;
            case "SCALE_IN", "REPLACED", "USER" -> EndCause.TERMINATED;
            default -> EndCause.UNKNOWN;
        };
    }
}
