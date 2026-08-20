package com.xammer.cloud.service;

import com.xammer.cloud.domain.CachedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Applies a maximum age to PostgreSQL snapshots of cloud API results.
 *
 * <p>The optimization services persist their results to {@code cached_data} so a Redis
 * restart does not trigger a full re-scan. Those reads previously returned a row
 * regardless of its {@code lastUpdated} value: the column was written but never checked.
 * A project whose Recommender API access broke months ago therefore kept serving its last
 * successful answer indefinitely, with nothing in the payload to say so.
 *
 * <p>This service centralises the age check so every provider behaves the same way, and
 * exposes the snapshot's age so callers can surface staleness to the user rather than
 * silently presenting old figures as current.
 */
@Service
public class SnapshotFreshnessService {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotFreshnessService.class);

    private final Duration maxAge;

    public SnapshotFreshnessService(
            @Value("${optimization.snapshot.max-age-hours:48}") long maxAgeHours) {
        if (maxAgeHours <= 0) {
            throw new IllegalStateException(
                    "optimization.snapshot.max-age-hours must be positive, got: " + maxAgeHours);
        }
        this.maxAge = Duration.ofHours(maxAgeHours);
        logger.info("Snapshot freshness window: {} hours", maxAgeHours);
    }

    /**
     * Returns the snapshot only when it is present and within the freshness window.
     *
     * @param snapshot result of a {@code cachedDataRepository.findById(...)} lookup
     * @param cacheKey key being read, for logging
     * @return the snapshot if usable, otherwise empty so the caller re-fetches
     */
    public Optional<CachedData> ifFresh(Optional<CachedData> snapshot, String cacheKey) {
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        CachedData data = snapshot.get();
        if (data.getLastUpdated() == null) {
            logger.warn("DB snapshot {} has no lastUpdated timestamp; treating it as stale", cacheKey);
            return Optional.empty();
        }
        Duration age = Duration.between(data.getLastUpdated(), LocalDateTime.now());
        if (age.compareTo(maxAge) > 0) {
            logger.info("DB snapshot {} is {} hours old (limit {}); refetching instead of serving stale data",
                    cacheKey, age.toHours(), maxAge.toHours());
            return Optional.empty();
        }
        return snapshot;
    }

    /**
     * Age of a snapshot in hours, or empty when unknown. Intended for disclosure in API
     * responses so the UI can label how current a figure is.
     */
    public Optional<Long> ageInHours(CachedData snapshot) {
        if (snapshot == null || snapshot.getLastUpdated() == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(snapshot.getLastUpdated(), LocalDateTime.now()).toHours());
    }

    /** The configured freshness window. */
    public Duration getMaxAge() {
        return maxAge;
    }
}
