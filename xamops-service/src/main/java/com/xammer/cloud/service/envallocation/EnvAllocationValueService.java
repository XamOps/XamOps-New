package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.service.cur.CurAllocationValueReader;
import com.xammer.cloud.service.cur.CurAllocationValueReader.Column;
import com.xammer.cloud.service.cur.CurAllocationValueReader.Values;
import com.xammer.cloud.service.cur.CurSourceResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The values the rule form offers for each match field, so a rule is built by picking from the
 * client's own bill rather than typing a guess.
 *
 * <p>Bill values come from the month the drawer previews. A rule still applies to every month it
 * runs on, so the form keeps free text for a value that only appears in another month.
 */
@Service
public class EnvAllocationValueService {

    /** What a picker can list: every match field that takes a value, plus the tag key itself. */
    public enum PickerField { SERVICE, USAGE_TYPE, REGION, ACCOUNT, CHARGE_TYPE, RESOURCE_ID, TAG, TAG_KEY, LABEL }

    /**
     * @param cost      unblended cost behind the value this month; null when not measured
     * @param resources resources carrying a label; null for every other field
     */
    public record MatchValue(String value, BigDecimal cost, Integer resources) {
    }

    /**
     * @param truncated true when more values exist than were returned; search to narrow
     * @param message   why the list is empty, when the report cannot answer
     */
    public record MatchValues(String field, String tagKey, String period, List<MatchValue> values,
                              boolean truncated, String message) {
    }

    /** A closed month's values do not change; the open month's are redelivered through the day. */
    private static final Duration CLOSED_MONTH_TTL = Duration.ofMinutes(30);
    private static final Duration OPEN_MONTH_TTL = Duration.ofMinutes(10);
    private static final int CACHE_SIZE = 200;

    private final CurSourceResolver curSourceResolver;
    private final CurAllocationValueReader valueReader;
    private final ResourceLabelAssignmentRepository labelAssignments;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EnvAllocationValueService(CurSourceResolver curSourceResolver,
                                     CurAllocationValueReader valueReader,
                                     ResourceLabelAssignmentRepository labelAssignments) {
        this.curSourceResolver = curSourceResolver;
        this.valueReader = valueReader;
        this.labelAssignments = labelAssignments;
    }

    public static PickerField parseField(String raw) {
        if (raw == null || raw.isBlank()) {
            throw EnvAllocationException.badRequest("Say which field to list values for.");
        }
        try {
            return PickerField.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw EnvAllocationException.badRequest("Unknown field '" + raw + "'.");
        }
    }

    public MatchValues values(Long clientId, CloudAccount account, YearMonth period, PickerField field,
                              String tagKey, String search) {
        String key = tagKey == null ? null : tagKey.trim();
        if (field == PickerField.TAG && (key == null || key.isEmpty())) {
            throw EnvAllocationException.badRequest("Say which tag to list values for.");
        }
        if (key != null && key.length() > 128) {
            throw EnvAllocationException.badRequest("Tag keys are at most 128 characters.");
        }
        String periodKey = period.toString();

        if (field == PickerField.LABEL) {
            return labels(clientId, account, periodKey, search);
        }
        if (field == PickerField.ACCOUNT) {
            // Every line read for allocation is pinned to this one account.
            return new MatchValues(field.name(), null, periodKey,
                    List.of(new MatchValue(account.getAwsAccountId(), null, null)), false, null);
        }

        String cacheKey = account.getAwsAccountId() + "|" + periodKey + "|" + field + "|"
                + (key == null ? "" : key.toLowerCase(Locale.ROOT)) + "|"
                + (search == null ? "" : search.trim().toLowerCase(Locale.ROOT));
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.values();
        }

        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        CurSourceResolver.Coverage coverage = curSourceResolver.describeCoverage(account, start, end, false);
        if (!coverage.covered()) {
            // Not cached: a report can be connected or finish backfilling at any moment.
            return new MatchValues(field.name(), key, periodKey, List.of(), false, coverage.message());
        }
        CurSource source = coverage.source().get();

        Values read = switch (field) {
            case TAG -> valueReader.tagValues(source, account, start, end, key, search);
            case TAG_KEY -> valueReader.tagKeys(source, account, start, end, search);
            case SERVICE -> valueReader.column(source, account, start, end, Column.SERVICE, search);
            case USAGE_TYPE -> valueReader.column(source, account, start, end, Column.USAGE_TYPE, search);
            case REGION -> valueReader.column(source, account, start, end, Column.REGION, search);
            case CHARGE_TYPE -> valueReader.column(source, account, start, end, Column.CHARGE_TYPE, search);
            case RESOURCE_ID -> valueReader.column(source, account, start, end, Column.RESOURCE_ID, search);
            case ACCOUNT, LABEL -> throw new IllegalStateException("handled above");
        };
        MatchValues out = new MatchValues(field.name(), key, periodKey,
                read.values().stream().map(v -> new MatchValue(v.value(), v.cost(), null)).toList(),
                read.truncated(), read.message());

        Duration ttl = period.isBefore(YearMonth.now()) ? CLOSED_MONTH_TTL : OPEN_MONTH_TTL;
        remember(cacheKey, out, ttl);
        return out;
    }

    /**
     * The client's labels that sit on at least one resource in this account, with how many. A
     * label on no resource here can never match a line of this account's bill.
     */
    private MatchValues labels(Long clientId, CloudAccount account, String periodKey, String search) {
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        Map<String, Set<String>> resourcesByLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResourceLabelAssignment a : labelAssignments.findByCloudAccountIdForClient(account.getId(), clientId)) {
            String name = a.getLabel().getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (needle != null && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Set<String> ids = resourcesByLabel.computeIfAbsent(name, k -> new HashSet<>());
            if (a.getResourceId() != null) {
                ids.add(a.getResourceId());
            }
        }
        List<MatchValue> values = new ArrayList<>(resourcesByLabel.size());
        resourcesByLabel.forEach((name, ids) -> values.add(new MatchValue(name, null, ids.size())));
        String message = values.isEmpty() && needle == null
                ? "No XamOps labels are on resources in this account yet. Add them under Resource Labels."
                : null;
        return new MatchValues(PickerField.LABEL.name(), null, periodKey, values, false, message);
    }

    private void remember(String key, MatchValues values, Duration ttl) {
        Instant now = Instant.now();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        while (cache.size() >= CACHE_SIZE) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
        cache.put(key, new Cached(values, now.plus(ttl)));
    }

    private record Cached(MatchValues values, Instant expiresAt) {
    }
}
