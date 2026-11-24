package com.xammer.cloud.service.autospotting.ledger.prices;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.AwsErrors;
import com.xammer.cloud.service.autospotting.aws.AwsEstimatePriceService;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code price-snapshot} for AWS (design-ledger.md §4.1, §13.1; plan-phase-17.md 17.1): Spot price history
 * hourly at :05 UTC and the Price List daily at 01:15 UTC, for the (region, zone, type, platform) keys of
 * managed groups. It runs under the {@code price-snapshot} job lease and writes {@code spot_price_snapshot}
 * rows only.
 *
 * <p><strong>A failed fetch writes nothing</strong> (AC-LED-10): each fetch is collected whole before the
 * first insert, so an error on any page, a missing field or an unreadable record leaves the table as it
 * was, and the affected entries become {@code UNKNOWN(MISSING_SPOT_PRICE)} or
 * {@code UNKNOWN(MISSING_OD_PRICE)} until a later fetch succeeds. A price is never defaulted, never
 * interpolated and never zero (a non-positive price is skipped).
 *
 * <p>AZ names are account-specific, so a Spot row's {@code zone} column is {@code <cloudAccountId>/<az>}:
 * two accounts that call different physical zones by the same name never share a price series.
 *
 * <p>Governed only by switch L ({@link LedgerGate}) and {@code scheduling.enabled}; never by switches (a)
 * to (e).
 */
@Component
public class AwsPriceSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(AwsPriceSnapshotJob.class);
    public static final String JOB = "price-snapshot";
    static final String SOURCE_SPOT = "AWS_SPOT_HISTORY";
    static final String SOURCE_LIST = "AWS_PRICE_LIST";
    /** On the first fetch of a pool there is no stored timestamp: read the last day, which includes the price in effect. */
    static final Duration FIRST_FETCH_LOOKBACK = Duration.ofDays(1);
    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final JobRunner jobRunner;
    private final LedgerGate gate;
    private final PriceKeyReader keys;
    private final PriceSnapshotStore store;
    private final AwsPriceSource source;
    private final CloudAccountRepository accounts;
    private final AutoSpottingMetrics metrics;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AwsPriceSnapshotJob(JobRunner jobRunner, LedgerGate gate, PriceKeyReader keys, PriceSnapshotStore store,
                               AwsPriceSource source, CloudAccountRepository accounts, AutoSpottingMetrics metrics) {
        this(jobRunner, gate, keys, store, source, accounts, metrics, Clock.systemUTC());
    }

    public AwsPriceSnapshotJob(JobRunner jobRunner, LedgerGate gate, PriceKeyReader keys, PriceSnapshotStore store,
                        AwsPriceSource source, CloudAccountRepository accounts, AutoSpottingMetrics metrics,
                        Clock clock) {
        this.jobRunner = jobRunner;
        this.gate = gate;
        this.keys = keys;
        this.store = store;
        this.source = source;
        this.accounts = accounts;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Spot price history, hourly at :05 UTC. */
    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void hourlySpot() {
        jobRunner.runLeased(JOB, "spot-" + HOUR_KEY.format(clock.instant()), () -> {
            if (gate.awsWriterOn()) {
                snapshotSpot();
            }
        });
    }

    /** List prices, daily at 01:15 UTC. */
    @Scheduled(cron = "0 15 1 * * *", zone = "UTC")
    public void dailyList() {
        jobRunner.runLeased(JOB, "list-" + DAY_KEY.format(clock.instant()), () -> {
            if (gate.awsWriterOn()) {
                snapshotList();
            }
        });
    }

    // ------------------------------------------------------------------ spot

    /** Fetches and stores Spot history for every key of managed groups; returns the rows written. */
    public int snapshotSpot() {
        Instant now = clock.instant();
        // account -> region -> platform -> type -> zones
        Map<Long, Map<String, Map<String, Map<String, TreeSet<String>>>>> plan = new TreeMap<>();
        for (PriceKeyReader.Key k : keys.read()) {
            String product = productDescription(k.platform());
            if (product == null || k.zone() == null || k.region() == null || k.region().isBlank()) {
                continue; // unmapped platform or no zone: nothing is priced, the entry stays UNKNOWN
            }
            plan.computeIfAbsent(k.cloudAccountId(), a -> new TreeMap<>())
                    .computeIfAbsent(k.region(), r -> new TreeMap<>())
                    .computeIfAbsent(k.platform(), p -> new TreeMap<>())
                    .computeIfAbsent(k.instanceType(), t -> new TreeSet<>()).add(k.zone());
        }
        int written = 0;
        for (var byAccount : plan.entrySet()) {
            Optional<CloudAccount> account = accounts.findById(byAccount.getKey());
            if (account.isEmpty()) {
                continue;
            }
            for (var byRegion : byAccount.getValue().entrySet()) {
                for (var byPlatform : byRegion.getValue().entrySet()) {
                    written += spotFor(account.get(), byRegion.getKey(), byPlatform.getKey(), byPlatform.getValue(), now);
                }
            }
        }
        return written;
    }

    private int spotFor(CloudAccount account, String region, String platform,
                        Map<String, TreeSet<String>> zonesByType, Instant now) {
        String product = productDescription(platform);
        Instant since = now.minus(FIRST_FETCH_LOOKBACK);
        for (var e : zonesByType.entrySet()) {
            for (String zone : e.getValue()) {
                Optional<Instant> last = store.latestSpotFrom(region, zoneKey(account.getId(), zone), e.getKey(), platform);
                if (last.isPresent() && last.get().isBefore(since)) {
                    since = last.get();
                }
            }
        }
        List<AwsPriceSource.SpotQuote> quotes;
        try {
            quotes = source.spotHistory(account, region, new ArrayList<>(zonesByType.keySet()), product, since);
        } catch (RuntimeException ex) {
            metrics.cloudError(Provider.AWS, "DescribeSpotPriceHistory", AwsErrors.classify(ex).name());
            log.warn("Spot price fetch failed for account {} region {}: {}; nothing written", account.getId(), region,
                    ex.getClass().getSimpleName());
            return 0;
        }
        int written = 0;
        for (AwsPriceSource.SpotQuote q : quotes) {
            TreeSet<String> wanted = zonesByType.get(q.instanceType());
            if (wanted == null || !wanted.contains(q.availabilityZone()) || q.price().signum() <= 0) {
                continue;
            }
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("availabilityZone", q.availabilityZone());
            payload.put("cloudAccountId", String.valueOf(account.getId()));
            payload.put("instanceType", q.instanceType());
            payload.put("productDescription", q.productDescription());
            payload.put("spotPrice", q.price().toPlainString());
            payload.put("timestamp", q.timestamp().toString());
            String json = CanonicalJson.compact(payload);
            if (store.insert(new PriceSnapshotStore.NewSnapshot(SOURCE_SPOT, "SPOT", region,
                    zoneKey(account.getId(), q.availabilityZone()), q.instanceType(), platform, q.price(), "HOUR", "USD",
                    q.timestamp(), null, "ec2-spot-price-history", true, clock.instant(),
                    CanonicalJson.sha256(json), json))) {
                written++;
            }
        }
        return written;
    }

    /** The {@code zone} column of a Spot snapshot: account-qualified, because AZ names are per account. */
    public static String zoneKey(long cloudAccountId, String zone) {
        return cloudAccountId + "/" + zone;
    }

    // ------------------------------------------------------------------ list prices

    /** Fetches and stores the list price of every (region, type, platform) in use; returns the rows written. */
    public int snapshotList() {
        Instant now = clock.instant();
        TreeSet<String> done = new TreeSet<>();
        int written = 0;
        for (PriceKeyReader.Key k : keys.read()) {
            if (k.platform() == null || k.region() == null || k.region().isBlank()) {
                continue;
            }
            String id = k.region() + "|" + k.instanceType() + "|" + k.platform();
            if (!done.add(id)) {
                continue;
            }
            Platform platform;
            try {
                platform = Platform.valueOf(k.platform());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String[] attrs = AwsEstimatePriceService.priceListAttributes(platform);
            if (attrs == null) {
                continue;
            }
            Optional<AwsPriceSource.ListPrice> price;
            try {
                price = source.listPrice(k.region(), k.instanceType(), attrs[0], attrs[1], attrs[2]);
            } catch (RuntimeException ex) {
                metrics.cloudError(Provider.AWS, "GetProducts", AwsErrors.classify(ex).name());
                log.warn("Price List fetch failed for {}: {}; nothing written", id, ex.getClass().getSimpleName());
                continue;
            }
            if (price.isEmpty()) {
                continue;
            }
            AwsPriceSource.ListPrice p = price.get();
            if (store.insert(new PriceSnapshotStore.NewSnapshot(SOURCE_LIST, "ON_DEMAND", k.region(), null,
                    k.instanceType(), k.platform(), p.ratePerHour(), "HOUR", "USD", p.effectiveFrom(), null,
                    p.sourceVersion(), true, now, CanonicalJson.sha256(p.rawRecord()), recordJson(p)))) {
                written++;
            }
        }
        return written;
    }

    private static String recordJson(AwsPriceSource.ListPrice p) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("effectiveFrom", p.effectiveFrom().toString());
        payload.put("ratePerHour", p.ratePerHour().toPlainString());
        payload.put("sourceVersion", p.sourceVersion());
        payload.put("recordSha256", CanonicalJson.sha256(p.rawRecord()));
        return CanonicalJson.compact(payload);
    }

    private static String productDescription(String platform) {
        if (platform == null) {
            return null;
        }
        try {
            return AwsEstimatePriceService.spotProductDescription(Platform.valueOf(platform));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
