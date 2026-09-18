package com.xammer.cloud.service.cur;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Caches the Cost and Usage Report insight sections so a page visit does not wait on Athena.
 *
 * <p>Every other panel on the Cost and FinOps pages reads a saved snapshot and renders at
 * once. These sections — charges by service, top resources, data transfer, tagging, price
 * versus volume and the rest — ran a live Athena query on every request, typically one to
 * three seconds each, so they were the only parts of the page that visibly loaded. The
 * browser-side cache in useCurInsight could not fix that: it lives in memory, so a reload,
 * a new tab or a fresh login started cold every time.
 *
 * <h2>Stale-while-revalidate</h2>
 * A cached answer is always returned immediately. When it is older than {@link #FRESH_FOR}
 * it is refreshed in the background, so the next visitor gets the newer figures without
 * anyone having waited for them. The report itself is only rebuilt by AWS one to three
 * times a day, so a few hours of age costs nothing real, whereas a blocking query on every
 * visit costs a visible delay every time.
 *
 * <p>Two tiers, as the dashboard snapshots are: Redis for speed, and cached_data so a Redis
 * restart or a deploy does not send every section back to Athena at once.
 *
 * <p>The only remaining wait is the very first view of a section, and the view straight
 * after "Refresh from CUR", which clears these entries on purpose — someone who has just
 * asked for fresh figures should get them, not a copy from this morning.
 */
@Service
public class CurInsightCache {

    private static final Logger logger = LoggerFactory.getLogger(CurInsightCache.class);

    /**
     * Key prefix. Public so the CUR refresh can clear an account's entries by prefix without
     * depending on this class. Laid out {@code CUR_INSIGHT::<account>::...} precisely so that
     * one account is one prefix.
     */
    public static final String PREFIX = "CUR_INSIGHT::";

    /** Past this age an entry is still served, but also refreshed behind the response. */
    private static final Duration FRESH_FOR = Duration.ofHours(6);

    /** Redis lifetime. Longer than FRESH_FOR so a stale entry exists to be served while it refreshes. */
    private static final int REDIS_TTL_MINUTES = 3 * 24 * 60;

    private static final String CUR_INSIGHTS_PATH = "/cur-insights/";

    /** What is kept: the rendered JSON and when it was produced. */
    public record Entry(String json, long storedAt) {
    }

    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    /** Keys with a background refresh already running, so a busy page does not start ten. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public CurInsightCache(RedisCacheService redisCache,
                           CachedDataRepository cachedDataRepository,
                           ObjectMapper objectMapper,
                           @Qualifier("awsTaskExecutor") Executor executor) {
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * The cached answer for the current request, computing it only when none exists.
     *
     * <p>Keyed on the request's own path and query string rather than on arguments the
     * caller passes, so a parameter added to an endpoint later — a tag key, a limit — is part
     * of the key automatically. A key assembled by hand is one that forgets the new parameter
     * and serves one tag's figures for another.
     *
     * <p>Only successful answers are kept. A 409 for "no report here" or a 502 for "Athena
     * failed" must not be remembered, or the section would keep showing the failure after
     * the cause was fixed.
     */
    public ResponseEntity<?> getOrCompute(String accountId, Supplier<ResponseEntity<?>> compute) {
        String key = keyForCurrentRequest(accountId);
        if (key == null) {
            return compute.get();
        }

        Entry hit = read(key);
        if (hit != null) {
            if (isStale(hit)) {
                refreshInBackground(key, compute);
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(hit.json());
        }

        ResponseEntity<?> fresh = compute.get();
        store(key, fresh);
        return fresh;
    }

    /**
     * Drops every cached section for an account.
     *
     * <p>Called by "Refresh from CUR": that button promises figures from the report as it is
     * now, and serving this morning's copy would quietly break the promise.
     */
    public void evictAccount(String accountId) {
        String prefix = PREFIX + accountId + "::";
        try {
            redisCache.evictByPattern(prefix + "*");
            cachedDataRepository.deleteByCacheKeyPrefix(prefix);
        } catch (Exception e) {
            // Leaves stale sections for at most FRESH_FOR — not worth failing a refresh over.
            logger.warn("[CUR] Could not clear cached insights for {}: {}", accountId, e.getMessage());
        }
    }

    private String keyForCurrentRequest(String accountId) {
        try {
            HttpServletRequest request = ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes()).getRequest();
            String uri = request.getRequestURI();
            int at = uri.indexOf(CUR_INSIGHTS_PATH);
            String endpoint = at >= 0 ? uri.substring(at + CUR_INSIGHTS_PATH.length()) : uri;
            String query = request.getQueryString();
            return PREFIX + accountId + "::" + endpoint + (query == null ? "" : "?" + query);
        } catch (IllegalStateException e) {
            // Not inside a request — nothing to key on, so do not cache.
            return null;
        }
    }

    private Entry read(String key) {
        try {
            Optional<Entry> redis = redisCache.get(key, Entry.class);
            if (redis.isPresent()) {
                return redis.get();
            }
            Optional<CachedData> row = cachedDataRepository.findById(key);
            if (row.isEmpty() || !looksLikeJson(row.get().getJsonData())) {
                return null;
            }
            LocalDateTime updated = row.get().getLastUpdated();
            long storedAt = updated == null ? 0L
                    : updated.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Entry entry = new Entry(row.get().getJsonData(), storedAt);
            redisCache.put(key, entry, REDIS_TTL_MINUTES);
            return entry;
        } catch (Exception e) {
            // A cache that cannot be read is a cache miss, never an error the reader sees.
            logger.debug("[CUR] Insight cache read failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void store(String key, ResponseEntity<?> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response.getBody());
            long now = System.currentTimeMillis();
            redisCache.put(key, new Entry(json, now), REDIS_TTL_MINUTES);

            CachedData row = cachedDataRepository.findById(key).orElse(new CachedData());
            row.setCacheKey(key);
            row.setJsonData(json);
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
        } catch (Exception e) {
            logger.debug("[CUR] Insight cache write failed for {}: {}", key, e.getMessage());
        }
    }

    private void refreshInBackground(String key, Supplier<ResponseEntity<?>> compute) {
        if (!refreshing.add(key)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    store(key, compute.get());
                } catch (Exception e) {
                    // The stale entry keeps being served; the next visit tries again.
                    logger.debug("[CUR] Background refresh failed for {}: {}", key, e.getMessage());
                } finally {
                    refreshing.remove(key);
                }
            });
        } catch (Exception e) {
            refreshing.remove(key);
        }
    }

    private boolean isStale(Entry entry) {
        return System.currentTimeMillis() - entry.storedAt() > FRESH_FOR.toMillis();
    }

    /**
     * Guards against rows written while CachedData.jsonData was a {@code @Lob}: on
     * PostgreSQL those hold a bare large-object OID, not JSON, and serving one would send the
     * browser a number where it expects an array.
     */
    private boolean looksLikeJson(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }
}
