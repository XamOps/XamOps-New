package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.domain.ResourceLabel;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.domain.ServiceLabelAssignment;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.ServiceLabelAssignmentRepository;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAllocationLineReader;
import com.xammer.cloud.service.cur.CurSourceResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each label costs in one month, from the resource labels, the service labels and their shares.
 *
 * <h2>How one bill line is divided</h2>
 * <ol>
 *   <li>The resource's own labels go first. Each takes its share of the line (a label with no share
 *       takes all of it).</li>
 *   <li>Whatever the resource labels leave — all of it for an unlabelled resource or a charge with
 *       no resource — goes to the labels on the line's service, each taking its share of that
 *       remainder.</li>
 *   <li>What is still left is unallocated.</li>
 * </ol>
 * So EC2 labelled "prod" with one instance labelled "uat 40%" sends that instance 40% to uat and
 * 60% to prod, and every other EC2 line to prod. No line is given out more than once, which is what
 * makes the label totals plus the unallocated figure add up to the bill.
 *
 * <p>Shares that add up to more than 100% on one item — possible only for labels added at full
 * cost from places that do not set a share, such as the Cloud List — are scaled down to 100%
 * together rather than counting the cost twice.
 *
 * <p>Labels are read live on every call, so a change shows on the next read. The month's bill lines
 * are cached briefly, because reading them is an Athena query.
 */
@Service
public class LabelSpendService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 6;
    /** Items listed per label. The totals and counts always cover everything. */
    private static final int MAX_ITEMS = 300;
    private static final Duration OPEN_MONTH_TTL = Duration.ofMinutes(10);
    private static final Duration CLOSED_MONTH_TTL = Duration.ofMinutes(30);
    private static final int CACHE_SIZE = 12;

    private final CurSourceResolver curSourceResolver;
    private final CurAllocationLineReader lineReader;
    private final ResourceLabelAssignmentRepository resourceAssignments;
    private final ServiceLabelAssignmentRepository serviceAssignments;
    private final ResourceIdResolver idResolver;
    private final Map<String, CachedLines> cache = new ConcurrentHashMap<>();

    public LabelSpendService(CurSourceResolver curSourceResolver,
                             CurAllocationLineReader lineReader,
                             ResourceLabelAssignmentRepository resourceAssignments,
                             ServiceLabelAssignmentRepository serviceAssignments,
                             ResourceIdResolver idResolver) {
        this.curSourceResolver = curSourceResolver;
        this.lineReader = lineReader;
        this.resourceAssignments = resourceAssignments;
        this.serviceAssignments = serviceAssignments;
        this.idResolver = idResolver;
    }

    // ── Response ─────────────────────────────────────────────────────────────────

    /**
     * @param status  OK, or NO_REPORT when the account has no usable Cost and Usage Report for the month
     * @param labels  every label with an assignment on this account, costliest first
     */
    public record Spend(String status, String message, String period, String basis,
                        BigDecimal billTotal, BigDecimal allocatedTotal, BigDecimal unallocatedTotal,
                        List<LabelTotal> labels) {
    }

    /**
     * @param share         percent of the bill
     * @param resourceCount distinct resources with any of this label's spend
     * @param itemCount     items before {@code items} was capped
     */
    public record LabelTotal(Long labelId, String name, String color, BigDecimal total, BigDecimal share,
                             int resourceCount, int itemCount, List<Item> items) {
    }

    /**
     * One resource's contribution to a label, or a service's resource-less charges.
     *
     * @param resourceId blank for charges with no resource behind them
     * @param sourceCost the resource's whole cost under this service this month
     * @param percent    amount ÷ sourceCost, or null when the cost nets to zero
     * @param via        "resource", "service", or "resource+service" — which label put it here
     */
    public record Item(String resourceId, String name, String service, String region,
                       BigDecimal sourceCost, BigDecimal amount, BigDecimal percent, String via) {
    }

    // ── Computation ──────────────────────────────────────────────────────────────

    public Spend spend(Long clientId, CloudAccount account, YearMonth period, CostBasis basis) {
        CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(
                account, period.atDay(1), period.atEndOfMonth(), false);
        if (!coverage.covered()) {
            return new Spend("NO_REPORT", coverage.message(), period.toString(), basis.wire(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        CurSource source = coverage.source().get();

        Map<String, List<Share>> byResource = resourceShares(clientId, account);
        Map<String, List<Share>> byService = serviceShares(clientId, account);

        Map<Long, LabelAcc> totals = new LinkedHashMap<>();
        BigDecimal bill = BigDecimal.ZERO;
        BigDecimal unallocated = BigDecimal.ZERO;

        for (CurAllocationLineReader.Line line : lines(source, account, period, basis)) {
            BigDecimal cost = line.cost().setScale(SCALE, RoundingMode.HALF_EVEN);
            bill = bill.add(cost);
            String rid = line.resourceId() == null ? "" : line.resourceId();

            BigDecimal remaining = cost;
            // Labels this line has reached, so its cost counts once in an item's "of" figure even
            // when one label is on both the resource and its service.
            Set<Long> reached = new HashSet<>();
            List<Share> own = rid.isEmpty() ? List.of() : byResource.getOrDefault(rid, List.of());
            if (!own.isEmpty()) {
                remaining = give(totals, own, cost, cost, line, rid, "resource", reached);
            }
            List<Share> svc = byService.getOrDefault(line.service(), List.of());
            if (!svc.isEmpty() && remaining.signum() != 0) {
                remaining = give(totals, svc, remaining, cost, line, rid, "service", reached);
            }
            unallocated = unallocated.add(remaining);
        }

        Map<String, String> names = names(account, totals.values());
        BigDecimal allocated = bill.subtract(unallocated);
        List<LabelTotal> out = new ArrayList<>();
        for (LabelAcc acc : totals.values()) {
            out.add(acc.toTotal(bill, names));
        }
        out.sort(Comparator.comparing(LabelTotal::total).reversed());
        return new Spend("OK", null, period.toString(), basis.wire(), money(bill), money(allocated),
                money(unallocated), out);
    }

    /**
     * Gives each label its share of {@code amount}, and returns what is left.
     *
     * <p>Shares over 100% together are scaled to 100% — see the class comment.
     */
    private static BigDecimal give(Map<Long, LabelAcc> totals, List<Share> shares, BigDecimal amount,
                                   BigDecimal lineCost, CurAllocationLineReader.Line line, String rid,
                                   String via, Set<Long> reached) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Share s : shares) sum = sum.add(s.percent());
        BigDecimal scale = sum.compareTo(HUNDRED) > 0 ? HUNDRED.divide(sum, 10, RoundingMode.HALF_EVEN) : BigDecimal.ONE;

        BigDecimal given = BigDecimal.ZERO;
        for (Share s : shares) {
            BigDecimal part = amount.multiply(s.percent()).multiply(scale)
                    .divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
            given = given.add(part);
            BigDecimal counted = reached.add(s.label().getId()) ? lineCost : BigDecimal.ZERO;
            totals.computeIfAbsent(s.label().getId(), k -> new LabelAcc(s.label()))
                    .add(rid, line.service(), line.region(), counted, part, via);
        }
        return amount.subtract(given);
    }

    private record Share(ResourceLabel label, BigDecimal percent) {
    }

    /** CUR resource id, in every form the report may use, to the labels on that resource. */
    private Map<String, List<Share>> resourceShares(Long clientId, CloudAccount account) {
        List<ResourceLabelAssignment> rows = resourceAssignments.findByCloudAccountIdForClient(account.getId(), clientId);
        if (rows.isEmpty()) return Map.of();

        Set<String> ids = new LinkedHashSet<>();
        for (ResourceLabelAssignment a : rows) {
            if (a.getResourceId() != null && !a.getResourceId().isBlank()) ids.add(a.getResourceId());
        }
        ResourceIdResolver.CurIdentifiers forms = idResolver.curIdentifiers(account.getId(), ids);

        // One share per label per resource: rows stored under two id forms of one resource are one label.
        Map<String, Map<Long, Share>> out = new HashMap<>();
        for (ResourceLabelAssignment a : rows) {
            String rid = a.getResourceId();
            if (rid == null || rid.isBlank()) continue;
            Set<String> keys = new HashSet<>(forms.byRequestedId().getOrDefault(rid, List.of()));
            keys.add(rid);
            Share share = new Share(a.getLabel(), percentOf(a.getSplitPercent()));
            for (String key : keys) {
                out.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(a.getLabel().getId(), share);
            }
        }
        Map<String, List<Share>> flat = new HashMap<>();
        out.forEach((k, v) -> flat.put(k, List.copyOf(v.values())));
        return flat;
    }

    private Map<String, List<Share>> serviceShares(Long clientId, CloudAccount account) {
        Map<String, List<Share>> out = new HashMap<>();
        for (ServiceLabelAssignment a : serviceAssignments.findByCloudAccountIdForClient(account.getId(), clientId)) {
            out.computeIfAbsent(a.getServiceName(), k -> new ArrayList<>())
                    .add(new Share(a.getLabel(), percentOf(a.getSplitPercent())));
        }
        return out;
    }

    private static BigDecimal percentOf(BigDecimal stored) {
        return stored == null ? HUNDRED : stored;
    }

    /** Names for the listed resources, from the inventory, in one read. */
    private Map<String, String> names(CloudAccount account, Iterable<LabelAcc> accs) {
        Set<String> ids = new LinkedHashSet<>();
        for (LabelAcc acc : accs) {
            for (ItemAcc item : acc.topItems()) {
                if (!item.resourceId.isEmpty()) ids.add(item.resourceId);
            }
        }
        if (ids.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        idResolver.resolve(account.getId(), ids).forEach((id, c) -> {
            if (c.resourceName() != null && !c.resourceName().isBlank()) out.put(id, c.resourceName());
        });
        return out;
    }

    // ── Bill lines, cached briefly ───────────────────────────────────────────────

    private List<CurAllocationLineReader.Line> lines(CurSource source, CloudAccount account, YearMonth period,
                                                     CostBasis basis) {
        String key = source.getId() + "|" + account.getAwsAccountId() + "|" + period + "|" + basis.wire();
        CachedLines hit = cache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.lines();
        }
        List<CurAllocationLineReader.Line> lines = lineReader
                .read(source, account, period.atDay(1), period.atEndOfMonth(), basis, List.of(), true)
                .lines();
        Duration ttl = period.isBefore(YearMonth.now()) ? CLOSED_MONTH_TTL : OPEN_MONTH_TTL;
        cache.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        while (cache.size() >= CACHE_SIZE) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
        cache.put(key, new CachedLines(lines, Instant.now().plus(ttl)));
        return lines;
    }

    private record CachedLines(List<CurAllocationLineReader.Line> lines, Instant expiresAt) {
    }

    // ── Accumulators ─────────────────────────────────────────────────────────────

    private static final class LabelAcc {
        final ResourceLabel label;
        BigDecimal total = BigDecimal.ZERO;
        /** Keyed by resource id and service; a resource-less charge keys on the service alone. */
        final Map<String, ItemAcc> items = new LinkedHashMap<>();
        List<ItemAcc> sorted;

        LabelAcc(ResourceLabel label) {
            this.label = label;
        }

        void add(String rid, String service, String region, BigDecimal lineCost, BigDecimal part, String via) {
            total = total.add(part);
            // lineCost arrives as zero when the same line already reached this label another way.
            items.computeIfAbsent(rid + "\u0000" + service, k -> new ItemAcc(rid, service, region))
                    .add(lineCost, part, via);
            sorted = null;
        }

        List<ItemAcc> topItems() {
            if (sorted == null) {
                sorted = items.values().stream()
                        .sorted(Comparator.comparing((ItemAcc i) -> i.amount.abs()).reversed())
                        .limit(MAX_ITEMS)
                        .toList();
            }
            return sorted;
        }

        LabelTotal toTotal(BigDecimal bill, Map<String, String> names) {
            Set<String> resources = new HashSet<>();
            for (ItemAcc i : items.values()) {
                if (!i.resourceId.isEmpty()) resources.add(i.resourceId);
            }
            List<Item> list = topItems().stream().map(i -> i.toItem(names.get(i.resourceId))).toList();
            BigDecimal share = bill.signum() == 0 ? BigDecimal.ZERO
                    : total.multiply(HUNDRED).divide(bill, 2, RoundingMode.HALF_EVEN);
            return new LabelTotal(label.getId(), label.getName(), label.getColor(), money(total), share,
                    resources.size(), items.size(), list);
        }
    }

    private static final class ItemAcc {
        final String resourceId;
        final String service;
        final String region;
        BigDecimal sourceCost = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        final Set<String> via = new TreeSet<>();

        ItemAcc(String resourceId, String service, String region) {
            this.resourceId = resourceId;
            this.service = service;
            this.region = region;
        }

        /** {@code lineCost} is zero when this line already counted toward the item. */
        void add(BigDecimal lineCost, BigDecimal part, String how) {
            sourceCost = sourceCost.add(lineCost);
            amount = amount.add(part);
            via.add(how);
        }

        Item toItem(String name) {
            BigDecimal percent = sourceCost.abs().compareTo(new BigDecimal("0.0001")) < 0 ? null
                    : amount.multiply(HUNDRED).divide(sourceCost, 2, RoundingMode.HALF_EVEN);
            return new Item(resourceId, name, service, region, money(sourceCost), money(amount), percent,
                    String.join("+", via));
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_EVEN);
    }
}
