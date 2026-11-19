package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GCP estimate pricing (design-gcp.md §6.4, core §9.5). It never feeds the ledger.
 *
 * <ul>
 *   <li>Shape from the template's {@code machineType} ({@link GcpMachineShapes}): shared-core types
 *       bill from {@code gcp-shared-core-v1}, never {@code guestCpus}; a type missing from it is not
 *       priced. No default type, no {@code -spot} name inference: the provisioning model is the
 *       caller's {@code spot()} versus {@code onDemand()} choice.</li>
 *   <li>Cloud Billing Catalog {@code listSkus} for Compute Engine, indexed per region with a TTL of
 *       24 h. Core and RAM are priced separately: {@code vCPU x core + GiB x RAM}, exact
 *       {@code unitPrice} conversion ({@link GcpSkuMatcher}).</li>
 *   <li>A catalog miss is {@code NOT_PRICED}. A failing catalog is {@code PRICING_UNAVAILABLE}, never
 *       cached, with a per-project backoff.</li>
 *   <li>No SUD or CUD is applied: the figure is an estimate (the ledger's measured numbers bill).</li>
 * </ul>
 * {@link PriceKey#zoneOrNull()} is the zone to read the machine type in (GCP machine types are zonal);
 * the price itself is regional.
 */
@Component
public class GcpEstimatePriceService implements EstimatePriceService {
    private static final Logger log = LoggerFactory.getLogger(GcpEstimatePriceService.class);
    public static final String SOURCE = "gcp-billing-catalog";
    static final Duration BACKOFF = Duration.ofMinutes(5);
    private static final Currency USD = Currency.getInstance("USD");

    private record Index(Instant asOf, List<JsonNode> skus) {
    }

    private final GcpViewSource views;
    private final GcpMachineShapes shapes;
    private final Clock clock;
    private final TtlCache<String, Index> indexes;
    private final Map<String, Instant> backoffUntil = new ConcurrentHashMap<>();

    @Autowired
    public GcpEstimatePriceService(GcpViewSource views, GcpMachineShapes shapes) {
        this(views, shapes, Clock.systemUTC());
    }

    GcpEstimatePriceService(GcpViewSource views, GcpMachineShapes shapes, Clock clock) {
        this.views = views;
        this.shapes = shapes;
        this.clock = clock;
        this.indexes = new TtlCache<>(Duration.ofHours(24), clock);
    }

    @Override
    public Provider provider() {
        return Provider.GCP;
    }

    @Override
    public PricedRate onDemand(CloudAccount account, PriceKey key) {
        return rate(account, key, false);
    }

    @Override
    public PricedRate spot(CloudAccount account, PriceKey key) {
        return rate(account, key, true);
    }

    private PricedRate rate(CloudAccount account, PriceKey key, boolean spot) {
        if (key.platform() != Platform.LINUX) {
            return PricedRate.notPriced(SOURCE); // OS licence premiums are not modelled
        }
        GcpReadOnlyView view;
        GcpMachineShapes.Shape shape;
        try {
            view = views.view(account);
            shape = shapes.shape(view, key.zoneOrNull(), key.typeOrMachineType());
        } catch (GcpReadException e) {
            // A type that cannot be read is not priced when the type is absent; any other failure is unavailable.
            return e.kind() == com.xammer.cloud.service.autospotting.spi.ErrorKind.NOT_FOUND
                    || e.kind() == com.xammer.cloud.service.autospotting.spi.ErrorKind.VALIDATION
                    ? PricedRate.notPriced(SOURCE) : PricedRate.unavailable(SOURCE);
        } catch (RuntimeException e) {
            return PricedRate.unavailable(SOURCE);
        }
        if (shape.billedVcpu() == null || !GcpSkuMatcher.accepts(shape.series())) {
            return PricedRate.notPriced(SOURCE);
        }
        String type = shape.name();
        if (type.endsWith("-ext")) {
            return PricedRate.notPriced(SOURCE); // extended memory has no matching row
        }
        boolean custom = type.contains("custom-");
        Index index;
        try {
            index = index(view, key.region());
        } catch (RuntimeException e) {
            log.debug("sku catalog unavailable for {}: {}", view.project(), GcpErrorMapper.classify(e));
            backoffUntil.put(view.project(), clock.instant().plus(BACKOFF));
            return PricedRate.unavailable(SOURCE);
        }
        Optional<GcpSkuMatcher.Rate> matched = GcpSkuMatcher.match(index.skus(), shape.series(), key.region(), custom, spot);
        if (matched.isEmpty()) {
            return PricedRate.notPriced(SOURCE);
        }
        BigDecimal perHour = shape.billedVcpu().multiply(matched.get().perVcpuHour())
                .add(shape.memoryGiB().multiply(matched.get().perGibHour()));
        return PricedRate.priced(perHour, USD, SOURCE, GcpSkuMatcher.VERSION, index.asOf());
    }

    private Index index(GcpReadOnlyView view, String region) {
        Instant until = backoffUntil.get(view.project());
        if (until != null && until.isAfter(clock.instant())) {
            throw new GcpReadException("skus.list", com.xammer.cloud.service.autospotting.spi.ErrorKind.TRANSIENT);
        }
        return indexes.get(view.project() + "|" + region, () -> {
            List<JsonNode> all = view.reads().listCatalogSkus();
            List<JsonNode> inRegion = all.stream().filter(s -> {
                for (JsonNode r : s.path("serviceRegions")) {
                    if (region.equals(r.asText())) {
                        return true;
                    }
                }
                return false;
            }).toList();
            return new Index(clock.instant(), inRegion);
        });
    }
}
