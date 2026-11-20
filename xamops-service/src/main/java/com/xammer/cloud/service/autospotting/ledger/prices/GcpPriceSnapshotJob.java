package com.xammer.cloud.service.autospotting.ledger.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.gcp.GcpErrorMapper;
import com.xammer.cloud.service.autospotting.gcp.GcpMachineShapes;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.gcp.GcpSharedCoreTable;
import com.xammer.cloud.service.autospotting.gcp.GcpSkuMatcher;
import com.xammer.cloud.service.autospotting.gcp.GcpViewSource;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code price-snapshot} for GCP (design-ledger.md §4.2, §13.1; plan-phase-30.md 30.1): Cloud Billing Catalog SKUs
 * daily at 01:15 UTC for the (region, series, model) keys of managed groups, stored in {@code spot_price_snapshot}
 * with the core (per vCPU-hour) and RAM (per GiB-hour) prices as separate rows. The machine shapes (billed vCPU from
 * {@code gcp-shared-core-v1}, memory) are stored beside them. Spot prices change at most about once in 30 days, so a
 * daily fetch with {@code pricingInfo.effectiveTime} records the cadence (V-LED-3); an unchanged price dedupes to no
 * row.
 *
 * <p><strong>A failed fetch writes nothing</strong> (AC-LED-10): the Catalog is read whole before the first insert and
 * a SKU set that does not price a series unambiguously stores no row for it, so the entry stays UNKNOWN. A price is
 * never defaulted. Amounts come from {@code units + nanos / 10^9} exactly.
 *
 * <p>Governed only by switch L ({@link LedgerGate#gcpWriterOn()}) and {@code scheduling.enabled}. It only reads GCP.
 */
@Component
public class GcpPriceSnapshotJob {
    private static final Logger log = LoggerFactory.getLogger(GcpPriceSnapshotJob.class);
    public static final String JOB = "price-snapshot";
    static final String SOURCE = "GCP_CATALOG";
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final JobRunner jobRunner;
    private final LedgerGate gate;
    private final PriceKeyReader keys;
    private final PriceSnapshotStore store;
    private final GcpShapeStore shapeStore;
    private final GcpViewSource views;
    private final GcpMachineShapes shapes;
    private final CloudAccountRepository accounts;
    private final AutoSpottingMetrics metrics;
    private final Clock clock;

    @Autowired
    public GcpPriceSnapshotJob(JobRunner jobRunner, LedgerGate gate, PriceKeyReader keys, PriceSnapshotStore store,
                               GcpShapeStore shapeStore, GcpViewSource views, GcpMachineShapes shapes,
                               CloudAccountRepository accounts, AutoSpottingMetrics metrics) {
        this(jobRunner, gate, keys, store, shapeStore, views, shapes, accounts, metrics, Clock.systemUTC());
    }

    public GcpPriceSnapshotJob(JobRunner jobRunner, LedgerGate gate, PriceKeyReader keys, PriceSnapshotStore store,
                               GcpShapeStore shapeStore, GcpViewSource views, GcpMachineShapes shapes,
                               CloudAccountRepository accounts, AutoSpottingMetrics metrics, Clock clock) {
        this.jobRunner = jobRunner;
        this.gate = gate;
        this.keys = keys;
        this.store = store;
        this.shapeStore = shapeStore;
        this.views = views;
        this.shapes = shapes;
        this.accounts = accounts;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Catalog SKUs, daily at 01:15 UTC. */
    @Scheduled(cron = "0 15 1 * * *", zone = "UTC")
    public void daily() {
        jobRunner.runLeased(JOB, "gcp-catalog-" + DAY_KEY.format(clock.instant()), () -> {
            if (gate.gcpWriterOn()) {
                snapshot();
            }
        });
    }

    /** Fetches and stores the Catalog prices and shapes of every managed key; returns the rows written. */
    public int snapshot() {
        // account -> region -> zone -> machine types
        Map<Long, Map<String, Map<String, TreeSet<String>>>> plan = new TreeMap<>();
        for (PriceKeyReader.GcpKey k : keys.readGcp()) {
            plan.computeIfAbsent(k.cloudAccountId(), a -> new TreeMap<>())
                    .computeIfAbsent(k.region(), r -> new TreeMap<>())
                    .computeIfAbsent(k.zone(), z -> new TreeSet<>()).add(k.machineType());
        }
        int written = 0;
        for (var byAccount : plan.entrySet()) {
            Optional<CloudAccount> account = accounts.findById(byAccount.getKey());
            if (account.isEmpty()) {
                continue;
            }
            written += forAccount(account.get(), byAccount.getValue());
        }
        return written;
    }

    private int forAccount(CloudAccount account, Map<String, Map<String, TreeSet<String>>> byRegion) {
        GcpReadOnlyView view;
        List<JsonNode> skus;
        try {
            view = views.view(account);
            skus = view.reads().listCatalogSkus();
        } catch (RuntimeException e) {
            metrics.cloudError(Provider.GCP, "skus.list", GcpErrorMapper.classify(e).name());
            log.warn("Catalog fetch failed for account {}: {}; nothing written", account.getId(),
                    e.getClass().getSimpleName());
            return 0;
        }
        Instant now = clock.instant();
        int written = 0;
        for (var region : byRegion.entrySet()) {
            TreeSet<String> series = new TreeSet<>();
            for (var zone : region.getValue().entrySet()) {
                for (String type : zone.getValue()) {
                    GcpMachineShapes.Shape shape;
                    try {
                        shape = shapes.shape(view, zone.getKey(), type);
                    } catch (RuntimeException e) {
                        metrics.cloudError(Provider.GCP, "machineTypes.get", GcpErrorMapper.classify(e).name());
                        continue; // no shape, no price: the entry stays UNKNOWN
                    }
                    if (shapeStore.insert(new GcpShapeStore.StoredShape(type, shape.series(), shape.billedVcpu(),
                            shape.memoryGiB(), shape.sharedCore(), GcpSharedCoreTable.v1().version()), now)) {
                        written++;
                    }
                    if (GcpSkuMatcher.accepts(shape.series()) && !type.endsWith("-ext") && !type.contains("custom-")) {
                        series.add(shape.series()); // a custom type has no row in the ledger's price keys: it stays UNKNOWN
                    }
                }
            }
            for (String s : series) {
                for (boolean spot : new boolean[] {false, true}) {
                    written += priceOf(skus, region.getKey(), s, spot, now);
                }
            }
        }
        return written;
    }

    private int priceOf(List<JsonNode> skus, String region, String series, boolean spot, Instant now) {
        Optional<GcpSkuMatcher.Rate> rate = GcpSkuMatcher.match(skus, series, region, false, spot);
        if (rate.isEmpty()) {
            return 0;
        }
        Optional<String> stated = GcpSkuMatcher.effectiveTime(skus, series, region, false, spot);
        Instant from = stated.map(Instant::parse).orElse(now);
        String kind = spot ? "SPOT" : "ON_DEMAND";
        String seriesKey = series;
        int written = 0;
        for (boolean core : new boolean[] {true, false}) {
            String resource = core ? "CORE" : "RAM";
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("effectiveTime", stated.orElse(null));
            payload.put("matcher", GcpSkuMatcher.VERSION);
            payload.put("model", kind);
            payload.put("rate", (core ? rate.get().perVcpuHour() : rate.get().perGibHour()).toPlainString());
            payload.put("region", region);
            payload.put("resource", resource);
            payload.put("series", seriesKey);
            String json = CanonicalJson.compact(payload);
            if (store.insert("GCP", new PriceSnapshotStore.NewSnapshot(SOURCE, kind, region, null,
                    seriesKey + ":" + resource, null, core ? rate.get().perVcpuHour() : rate.get().perGibHour(),
                    core ? "VCPU_HOUR" : "GIB_HOUR", "USD", from, null,
                    GcpSkuMatcher.VERSION + ":" + CanonicalJson.sha256(json).substring(0, 16), stated.isPresent(), now,
                    CanonicalJson.sha256(json), json))) {
                written++;
            }
        }
        return written;
    }
}
