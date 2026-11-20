package com.xammer.cloud.service.autospotting.ledger.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentsStatus;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Gcp;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Group;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Interval;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Original;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PreType;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Shape;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Sud;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.UnitPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.GcpSudConfig;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.prices.GcpShapeStore;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceEligibility;
import com.xammer.cloud.service.autospotting.ledger.prices.PriceKeyReader;
import com.xammer.cloud.service.autospotting.ledger.reconcile.GcpBillSourceResolver;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The GCP ledger inputs (design-ledger.md §13.2 step 3, §4.2, plan-phase-30.md 30.3): the shared working-table reads
 * of {@link JdbcLedgerInputsSource} for provider {@code GCP}, plus what {@code gcp-1} needs: the stored machine
 * shapes (billed vCPU from {@code gcp-shared-core-v1}), the core and RAM Catalog snapshots, the SUD config, the
 * account's billing source (PD-06) and the contract's declared discount.
 *
 * <p>Defaults are the design's safe ones, never guesses: the reconciliation tier is {@code MEASURED_ONLY} (the
 * export is read in Phase 31), commitments are {@code UNAVAILABLE} (capped, no deduction), and with no export the
 * SUD is the series maximum (PO-08). A price, shape or SUD entry that is missing stays missing, so the entry is UNKNOWN.
 */
@Repository("gcpLedgerInputs")
public class GcpLedgerInputsSource extends JdbcLedgerInputsSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final GcpShapeStore shapes;

    public GcpLedgerInputsSource(DataSource dataSource) {
        super(dataSource, "GCP");
        this.shapes = new GcpShapeStore(dataSource);
    }

    /** {@code project/location/name}: the region is the location's (a zone loses its suffix). */
    @Override
    protected String regionOf(String groupKey) {
        String[] p = groupKey == null ? new String[0] : groupKey.split("/", -1);
        return p.length == 3 ? PriceKeyReader.regionOf(p[1]) : null;
    }

    /** The original machine types: those of the snapshot's templates, each at weight 1; else the plan's floor type. */
    @Override
    protected Original originalOf(String snapshotPayload, JsonNode plan) {
        if (snapshotPayload == null) {
            return null;
        }
        try {
            TreeSet<String> types = new TreeSet<>();
            for (JsonNode t : JSON.readTree(snapshotPayload).path("templates")) {
                String type = t.path("resource").path("properties").path("machineType").asText(null);
                if (type != null) {
                    types.add(type.substring(type.lastIndexOf('/') + 1));
                }
            }
            if (types.isEmpty() && plan != null && plan.path("onDemandFloor").hasNonNull("instanceType")) {
                types.add(plan.path("onDemandFloor").path("instanceType").asText());
            }
            List<PreType> pre = new ArrayList<>();
            for (String type : types) {
                pre.add(new PreType(type, null, BigDecimal.ONE, null));
            }
            return new Original(BigDecimal.ZERO, BigDecimal.valueOf(100L), pre);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    protected CalcInputs assemble(Parts p) {
        TreeSet<String> types = new TreeSet<>();
        for (Interval iv : p.intervals()) {
            types.add(iv.instanceType());
        }
        if (p.original() != null) {
            for (PreType pre : p.original().preEnable()) {
                types.add(pre.instanceType());
            }
        }
        List<Shape> shapeList = new ArrayList<>();
        for (GcpShapeStore.StoredShape s : shapes.forTypes(new ArrayList<>(types))) {
            shapeList.add(new Shape(s.machineType(), s.series(), s.billedVcpu(), s.memoryGib(), s.sharedCore()));
        }
        TreeSet<String> series = new TreeSet<>();
        shapeList.forEach(s -> series.add(s.series()));
        List<UnitPrice> prices = new ArrayList<>();
        List<Sud> sud = new ArrayList<>();
        GcpSudConfig config = GcpSudConfig.v1();
        for (String s : series) {
            for (String kind : List.of("ON_DEMAND", "SPOT")) {
                for (String resource : List.of("CORE", "RAM")) {
                    prices.addAll(unitPrices(p, s, kind, resource));
                }
            }
            config.forSeries(s).ifPresent(c -> sud.add(new Sud(s, c.maxRate(), c.tiers(), null, null, List.of(),
                    List.of(), null)));
        }
        prices.sort(Comparator.comparing(UnitPrice::snapshotId));
        GcpBillSourceResolver.Resolved bill = billSource(p.accountId());
        Gcp gcp = new Gcp(bill.source().name(), com.xammer.cloud.service.autospotting.gcp.GcpSharedCoreTable.v1().version(),
                p.declaredDiscount(), shapeList, prices, sud, List.of());
        return new CalcInputs(SavingsCalculator.GCP_VERSION, SavingsCalculator.GCP_PROVIDER, "USD", p.start(), p.end(),
                new Group(p.g().id().toString(), p.accountId(), p.g().clientId(), p.region()), p.goLive(),
                p.contract(), p.feeBasis(), Tier.MEASURED_ONLY, p.transitions(), p.revertOps(), p.intervals(),
                p.samples(), List.of(), List.of(), p.original(), CommitmentsStatus.UNAVAILABLE, List.of(), gcp);
    }

    private GcpBillSourceResolver.Resolved billSource(long accountId) {
        return jdbc.query("SELECT billing_source, csp_managed FROM cloud_account WHERE id = ?",
                (rs, i) -> GcpBillSourceResolver.resolve(rs.getString(1), rs.getBoolean(2)), accountId)
                .stream().findFirst().orElse(GcpBillSourceResolver.resolve((String) null, false));
    }

    /**
     * The eligible Catalog snapshots of one series, model and resource for the day (design-ledger.md §4.4): On-Demand
     * is the latest one that may price the whole day; Spot is the day's series (clamped and closed at the next row).
     */
    private List<UnitPrice> unitPrices(Parts p, String series, String kind, String resource) {
        String sku = series + ":" + resource;
        List<PriceEligibility.Snapshot> rows = new ArrayList<>(jdbc.query("SELECT id, rate, effective_from, effective_to,"
                        + " source_effective_time_stated, fetched_at FROM spot_price_snapshot WHERE provider = 'GCP'"
                        + " AND source = 'GCP_CATALOG' AND price_kind = ? AND region = ? AND sku_or_type = ?"
                        + " AND effective_from < ? ORDER BY effective_from DESC, fetched_at DESC, id DESC LIMIT 8",
                (rs, i) -> new PriceEligibility.Snapshot(rs.getObject(1, UUID.class).toString(), rs.getBigDecimal(2),
                        sec(rs.getTimestamp(3)), sec(rs.getTimestamp(4)), rs.getBoolean(5), sec(rs.getTimestamp(6))),
                kind, p.region(), sku, Timestamp.from(p.end())));
        List<UnitPrice> out = new ArrayList<>();
        if ("ON_DEMAND".equals(kind)) {
            for (PriceEligibility.Snapshot s : rows) {
                if (PriceEligibility.mayPrice(s, p.start(), p.end())) {
                    out.add(new UnitPrice(s.id(), kind, series, resource, s.rate(), s.effectiveFrom(), null));
                    break;
                }
            }
            return out;
        }
        for (PriceEligibility.Snapshot s : PriceEligibility.forSeries(rows)) {
            if (s.effectiveTo() != null && !s.effectiveTo().isAfter(p.start())) {
                continue;
            }
            out.add(new UnitPrice(s.id(), kind, series, resource, s.rate(), s.effectiveFrom(), s.effectiveTo()));
        }
        return out;
    }

    private static Instant sec(Timestamp t) {
        return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
