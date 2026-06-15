package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.ScriptRating;
import com.xammer.cloud.domain.ScriptVersion;
import com.xammer.cloud.domain.UserScriptInteraction;
import com.xammer.cloud.dto.devops.CloudProvider;
import com.xammer.cloud.dto.devops.ScriptAnalytics;
import com.xammer.cloud.dto.devops.ScriptCreateRequest;
import com.xammer.cloud.dto.devops.ScriptFilters;
import com.xammer.cloud.dto.devops.ScriptUpdateRequest;
import com.xammer.cloud.dto.devops.SearchOptions;
import com.xammer.cloud.dto.devops.SearchQuery;
import com.xammer.cloud.dto.devops.SearchResult;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import com.xammer.cloud.repository.ScriptRatingRepository;
import com.xammer.cloud.repository.ScriptVersionRepository;
import com.xammer.cloud.repository.UserScriptInteractionRepository;
import com.xammer.cloud.service.cache.DevOpsScriptCacheInvalidationService;
import com.xammer.cloud.service.cache.DevOpsScriptCacheKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link IEnhancedDevOpsScriptService}.
 *
 * <p>Listing/filtering and full-text {@link #searchScripts} are both served from
 * PostgreSQL and delegated to {@link IScriptSearchService}.
 * Script details and filtered lists are cached in Redis; mutations invalidate
 * the relevant cache entries through {@link DevOpsScriptCacheInvalidationService}.</p>
 *
 * Requirements: REQ-2.1.1, REQ-2.1.3, REQ-2.3.2, REQ-2.4.1, REQ-2.4.2,
 *               REQ-2.4.3, REQ-2.4.4, REQ-2.6.3
 */
@Service
public class EnhancedDevOpsScriptService implements IEnhancedDevOpsScriptService {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedDevOpsScriptService.class);

    private static final String PUBLISHED = "published";
    private static final String DEFAULT_VERSION = "1.0.0";

    // Cache TTLs (per REQ-2.6.3): details 10min, lists 5min.
    private static final Duration SCRIPT_TTL = Duration.ofMinutes(10);
    private static final Duration LIST_TTL = Duration.ofMinutes(5);
    private static final Duration TRENDING_TTL = Duration.ofMinutes(5);

    // Trending parameters (per design Trending Scripts Calculation Algorithm).
    private static final int TRENDING_WINDOW_DAYS = 7;
    private static final double DECAY_RATE = 0.1;
    private static final double VELOCITY_THRESHOLD = 1.5;
    private static final double VELOCITY_BONUS_FACTOR = 20.0;

    // Interaction type constants.
    static final String VIEW = "view";
    static final String DOWNLOAD = "download";
    static final String COPY = "copy";
    static final String FAVORITE = "favorite";
    static final String UNFAVORITE = "unfavorite";

    private final DevOpsScriptRepository scriptRepository;
    private final ScriptRatingRepository ratingRepository;
    private final UserScriptInteractionRepository interactionRepository;
    private final ScriptVersionRepository versionRepository;
    private final IScriptSearchService searchService;
    private final DevOpsScriptCacheKeyGenerator keyGenerator;
    private final DevOpsScriptCacheInvalidationService cacheInvalidationService;
    private final RedisTemplate<String, Object> redisTemplate;

    public EnhancedDevOpsScriptService(
            DevOpsScriptRepository scriptRepository,
            ScriptRatingRepository ratingRepository,
            UserScriptInteractionRepository interactionRepository,
            ScriptVersionRepository versionRepository,
            IScriptSearchService searchService,
            DevOpsScriptCacheKeyGenerator keyGenerator,
            DevOpsScriptCacheInvalidationService cacheInvalidationService,
            @Qualifier("devOpsScriptRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.scriptRepository = scriptRepository;
        this.ratingRepository = ratingRepository;
        this.interactionRepository = interactionRepository;
        this.versionRepository = versionRepository;
        this.searchService = searchService;
        this.keyGenerator = keyGenerator;
        this.cacheInvalidationService = cacheInvalidationService;
        this.redisTemplate = redisTemplate;
    }

    // ------------------------------------------------------------------
    // Core CRUD
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<DevOpsScript> getAllScripts(ScriptFilters filters, Pageable pageable) {
        ScriptFilters effective = filters != null ? filters : new ScriptFilters();

        String cacheKey = listCacheKey(effective, pageable);
        @SuppressWarnings("unchecked")
        List<DevOpsScript> cached = (List<DevOpsScript>) cacheGet(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<DevOpsScript> published = scriptRepository.findByStatus(PUBLISHED);

        List<DevOpsScript> filtered = published.stream()
                .filter(s -> matchesFilters(s, effective))
                .sorted(comparatorFor(effective.getSortBy()))
                .collect(Collectors.toList());

        List<DevOpsScript> page = paginate(filtered, pageable);
        page.forEach(this::initializeCollections);

        cachePut(cacheKey, new ArrayList<>(page), LIST_TTL);
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public DevOpsScript getScriptById(Long id) {
        String cacheKey = keyGenerator.generateScriptKey(id);
        Object cached = cacheGet(cacheKey);
        if (cached instanceof DevOpsScript) {
            return (DevOpsScript) cached;
        }

        DevOpsScript script = requireScript(id);
        initializeCollections(script);

        cachePut(cacheKey, script, SCRIPT_TTL);
        return script;
    }

    @Override
    @Transactional
    public DevOpsScript createScript(ScriptCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Script create request must not be null");
        }

        DevOpsScript script = new DevOpsScript();
        script.setName(request.getName());
        script.setDescription(request.getDescription());
        script.setCategory(request.getCategory());
        script.setCode(request.getCode());
        script.setUsageInstructions(request.getUsageInstructions());
        script.setVersion(request.getVersion() != null ? request.getVersion() : DEFAULT_VERSION);
        script.setTags(new ArrayList<>(request.getTags()));
        script.setCloudProviders(new ArrayList<>(request.getCloudProviders()));
        script.setDifficulty(request.getDifficulty());
        script.setAuthor(request.getAuthor());
        script.setAuthorId(request.getAuthorId());
        script.setEstimatedTimeMinutes(request.getEstimatedTimeMinutes());
        script.setPrerequisites(request.getPrerequisites());
        script.setRelatedDocumentationUrl(request.getRelatedDocumentationUrl());
        script.setStatus(request.getStatus() != null ? request.getStatus() : PUBLISHED);

        DevOpsScript saved = scriptRepository.save(script);

        // Record the initial version snapshot for version history (REQ-2.7.1).
        versionRepository.save(new ScriptVersion(saved, saved.getVersion(), saved.getCode(),
                "Initial version", saved.getAuthor()));

        safeIndex(saved);
        cacheInvalidationService.invalidateAllListCaches();
        cacheInvalidationService.invalidateAllSearchCaches();

        logger.info("Created DevOps script {} ({})", saved.getId(), saved.getName());
        return saved;
    }

    @Override
    @Transactional
    public DevOpsScript updateScript(Long id, ScriptUpdateRequest request) {
        DevOpsScript script = requireScript(id);
        if (request == null) {
            return script;
        }

        if (request.getName() != null) script.setName(request.getName());
        if (request.getDescription() != null) script.setDescription(request.getDescription());
        if (request.getCategory() != null) script.setCategory(request.getCategory());
        if (request.getCode() != null) script.setCode(request.getCode());
        if (request.getUsageInstructions() != null) script.setUsageInstructions(request.getUsageInstructions());
        if (request.getVersion() != null) script.setVersion(request.getVersion());
        if (request.getTags() != null) script.setTags(new ArrayList<>(request.getTags()));
        if (request.getCloudProviders() != null) script.setCloudProviders(new ArrayList<>(request.getCloudProviders()));
        if (request.getDifficulty() != null) script.setDifficulty(request.getDifficulty());
        if (request.getAuthor() != null) script.setAuthor(request.getAuthor());
        if (request.getAuthorId() != null) script.setAuthorId(request.getAuthorId());
        if (request.getEstimatedTimeMinutes() != null) script.setEstimatedTimeMinutes(request.getEstimatedTimeMinutes());
        if (request.getPrerequisites() != null) script.setPrerequisites(request.getPrerequisites());
        if (request.getRelatedDocumentationUrl() != null) script.setRelatedDocumentationUrl(request.getRelatedDocumentationUrl());
        if (request.getStatus() != null) script.setStatus(request.getStatus());
        if (request.getIsFeatured() != null) script.setIsFeatured(request.getIsFeatured());

        DevOpsScript saved = scriptRepository.save(script);

        safeIndex(saved);
        cacheInvalidationService.invalidateScriptAndDependencies(saved.getId());

        logger.info("Updated DevOps script {}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public void deleteScript(Long id) {
        DevOpsScript script = requireScript(id);
        scriptRepository.delete(script);

        safeDeleteFromIndex(id);
        cacheInvalidationService.invalidateOnScriptDelete(id);

        logger.info("Deleted DevOps script {}", id);
    }

    // ------------------------------------------------------------------
    // Search & discovery
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SearchResult<DevOpsScript> searchScripts(String query, SearchOptions options) {
        SearchOptions opts = options != null ? options : new SearchOptions();
        ScriptFilters filters = opts.getFilters();

        SearchQuery searchQuery = new SearchQuery(query);
        searchQuery.setCategories(filters.getCategories());
        searchQuery.setCloudProviders(filters.getCloudProviders());
        searchQuery.setDifficulties(filters.getDifficulties());
        searchQuery.setTags(filters.getTags());
        searchQuery.setMinRating(filters.getMinRating());
        searchQuery.setSortBy(filters.getSortBy());
        searchQuery.setStatus(PUBLISHED);
        searchQuery.setPage(opts.getPage());
        searchQuery.setSize(opts.getPageSize());

        return searchService.search(searchQuery);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DevOpsScript> getScriptsByTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> wanted = tags.stream()
                .filter(t -> t != null && !t.trim().isEmpty())
                .map(t -> t.trim().toLowerCase())
                .collect(Collectors.toList());

        List<DevOpsScript> result = scriptRepository.findByStatus(PUBLISHED).stream()
                .filter(s -> s.getTags() != null && s.getTags().stream()
                        .anyMatch(tag -> tag != null && wanted.contains(tag.toLowerCase())))
                .collect(Collectors.toList());
        result.forEach(this::initializeCollections);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DevOpsScript> getScriptsByCloudProvider(CloudProvider provider) {
        if (provider == null) {
            return new ArrayList<>();
        }
        String code = provider.getCode();
        List<DevOpsScript> result = scriptRepository.findByStatus(PUBLISHED).stream()
                .filter(s -> s.getCloudProviders() != null && s.getCloudProviders().stream()
                        .anyMatch(p -> p != null && p.equalsIgnoreCase(code)))
                .collect(Collectors.toList());
        result.forEach(this::initializeCollections);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DevOpsScript> getTrendingScripts(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }

        String cacheKey = keyGenerator.generateTrendingKey(TRENDING_WINDOW_DAYS + "d", limit);
        Object cached = cacheGet(cacheKey);
        if (cached instanceof List) {
            @SuppressWarnings("unchecked")
            List<DevOpsScript> cachedList = (List<DevOpsScript>) cached;
            return cachedList;
        }

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(TRENDING_WINDOW_DAYS);

        // Load all interactions in the window once, then group per script.
        List<UserScriptInteraction> interactions =
                interactionRepository.findByTimestampBetween(startTime, endTime);
        Map<Long, List<UserScriptInteraction>> byScript = interactions.stream()
                .filter(i -> i.getScriptId() != null)
                .collect(Collectors.groupingBy(UserScriptInteraction::getScriptId));

        LocalDateTime last24Start = endTime.minusDays(1);
        LocalDateTime prev24Start = endTime.minusDays(2);

        Map<Long, Double> scores = new HashMap<>();
        for (DevOpsScript script : scriptRepository.findByStatus(PUBLISHED)) {
            List<UserScriptInteraction> scriptInteractions =
                    byScript.getOrDefault(script.getId(), new ArrayList<>());
            double score = computeTrendingScore(script, scriptInteractions,
                    endTime, last24Start, prev24Start);
            if (score > 0) {
                scores.put(script.getId(), score);
            }
        }

        List<Long> orderedIds = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        List<DevOpsScript> trending = hydrateInOrder(orderedIds);
        trending.forEach(this::initializeCollections);

        cachePut(cacheKey, new ArrayList<>(trending), TRENDING_TTL);
        return trending;
    }

    /**
     * Time-decayed trending score for a single script. Guaranteed to be
     * non-negative and positive only when recent interactions exist
     * (Property 7: Trending Score Non-Negativity).
     */
    private double computeTrendingScore(DevOpsScript script,
                                        List<UserScriptInteraction> interactions,
                                        LocalDateTime endTime,
                                        LocalDateTime last24Start,
                                        LocalDateTime prev24Start) {
        double score = 0.0;
        int recentCount = 0;
        int previousCount = 0;

        for (UserScriptInteraction interaction : interactions) {
            LocalDateTime ts = interaction.getTimestamp();
            if (ts == null) {
                continue;
            }
            double ageInDays = secondsBetween(ts, endTime) / 86400.0;
            if (ageInDays < 0) {
                ageInDays = 0;
            }
            double decayFactor = Math.exp(-DECAY_RATE * ageInDays);
            score += interactionWeight(interaction.getInteractionType()) * decayFactor;

            if (!ts.isBefore(last24Start)) {
                recentCount++;
            } else if (!ts.isBefore(prev24Start)) {
                previousCount++;
            }
        }

        // Velocity bonus: reward rapid growth versus the previous day.
        if (previousCount > 0) {
            double velocityRatio = (double) recentCount / previousCount;
            if (velocityRatio > VELOCITY_THRESHOLD) {
                score += (velocityRatio - 1.0) * VELOCITY_BONUS_FACTOR;
            }
        }

        // Quality multiplier: boost well-rated scripts.
        double avgRating = script.getAverageRating() != null ? script.getAverageRating() : 0.0;
        int ratingCount = script.getRatingCount() != null ? script.getRatingCount() : 0;
        if (avgRating >= 4.0 && ratingCount >= 3) {
            double qualityMultiplier = 1.0 + ((avgRating - 3.0) / 10.0);
            score *= qualityMultiplier;
        }

        return Math.max(0.0, score);
    }

    private double interactionWeight(String type) {
        if (type == null) {
            return 0.0;
        }
        switch (type) {
            case VIEW:
                return 1.0;
            case DOWNLOAD:
                return 5.0;
            case COPY:
                return 3.0;
            case FAVORITE:
                return 10.0;
            case "rating":
                return 15.0;
            default:
                return 0.0;
        }
    }

    // ------------------------------------------------------------------
    // User interactions
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void recordScriptView(Long scriptId, String userId) {
        DevOpsScript script = requireScript(scriptId);
        script.setViewCount(safe(script.getViewCount()) + 1);
        script.setLastUsedAt(LocalDateTime.now());
        scriptRepository.save(script);
        recordInteraction(scriptId, userId, VIEW);
        cacheInvalidationService.invalidateScript(scriptId);
    }

    @Override
    @Transactional
    public void recordScriptDownload(Long scriptId, String userId) {
        DevOpsScript script = requireScript(scriptId);
        script.setDownloadCount(safe(script.getDownloadCount()) + 1);
        // Keep the legacy downloads field in sync.
        script.setDownloads(script.getDownloadCount());
        script.setLastUsedAt(LocalDateTime.now());
        scriptRepository.save(script);
        recordInteraction(scriptId, userId, DOWNLOAD);
        cacheInvalidationService.invalidateScript(scriptId);
    }

    @Override
    @Transactional
    public void recordScriptCopy(Long scriptId, String userId) {
        DevOpsScript script = requireScript(scriptId);
        script.setCopyCount(safe(script.getCopyCount()) + 1);
        script.setLastUsedAt(LocalDateTime.now());
        scriptRepository.save(script);
        recordInteraction(scriptId, userId, COPY);
        cacheInvalidationService.invalidateScript(scriptId);
    }

    @Override
    @Transactional
    public ScriptRating rateScript(Long scriptId, String userId, int rating, String review) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        DevOpsScript script = requireScript(scriptId);

        ScriptRating scriptRating = ratingRepository.findByScriptIdAndUserId(scriptId, userId)
                .orElseGet(() -> new ScriptRating(script, userId, rating, review));
        scriptRating.setScript(script);
        scriptRating.setRating(rating);
        scriptRating.setReview(review);
        ScriptRating savedRating = ratingRepository.save(scriptRating);

        recomputeAverageRating(script);
        scriptRepository.save(script);

        cacheInvalidationService.invalidateOnRatingUpdate(scriptId);
        return savedRating;
    }

    /**
     * Recompute and apply a script's average rating and rating count from all of
     * its persisted ratings. The average of N ratings in [1,5] is itself in
     * [1,5] (Property: Average Rating Calculation).
     */
    private void recomputeAverageRating(DevOpsScript script) {
        List<ScriptRating> ratings = ratingRepository.findByScriptId(script.getId());
        int count = ratings.size();
        if (count == 0) {
            script.setAverageRating(0.0);
            script.setRatingCount(0);
            return;
        }
        double sum = 0.0;
        for (ScriptRating r : ratings) {
            sum += r.getRating() != null ? r.getRating() : 0;
        }
        double average = sum / count;
        // Round to two decimals for stable, display-friendly values.
        average = Math.round(average * 100.0) / 100.0;
        script.setAverageRating(average);
        script.setRatingCount(count);
    }

    @Override
    @Transactional
    public void favoriteScript(Long scriptId, String userId) {
        DevOpsScript script = requireScript(scriptId);
        if (isFavorited(scriptId, userId)) {
            return; // idempotent
        }
        script.setFavoriteCount(safe(script.getFavoriteCount()) + 1);
        scriptRepository.save(script);
        recordInteraction(scriptId, userId, FAVORITE);
        cacheInvalidationService.invalidateUserFavorites(userId);
        cacheInvalidationService.invalidateScript(scriptId);
    }

    @Override
    @Transactional
    public void unfavoriteScript(Long scriptId, String userId) {
        DevOpsScript script = requireScript(scriptId);
        if (!isFavorited(scriptId, userId)) {
            return; // idempotent
        }
        script.setFavoriteCount(Math.max(0, safe(script.getFavoriteCount()) - 1));
        scriptRepository.save(script);
        recordInteraction(scriptId, userId, UNFAVORITE);
        cacheInvalidationService.invalidateUserFavorites(userId);
        cacheInvalidationService.invalidateScript(scriptId);
    }

    /**
     * Current favorite state derived from the interaction log: favorited when the
     * user has more favorite events than unfavorite events for the script.
     */
    private boolean isFavorited(Long scriptId, String userId) {
        long favorites = interactionRepository
                .countByUserIdAndScriptIdAndInteractionType(userId, scriptId, FAVORITE);
        long unfavorites = interactionRepository
                .countByUserIdAndScriptIdAndInteractionType(userId, scriptId, UNFAVORITE);
        return favorites > unfavorites;
    }

    // ------------------------------------------------------------------
    // Analytics
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ScriptAnalytics getScriptAnalytics(Long scriptId) {
        DevOpsScript s = requireScript(scriptId);
        return toAnalytics(s);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScriptAnalytics> getPopularScripts(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        return scriptRepository.findByStatus(PUBLISHED).stream()
                .sorted(Comparator.comparingInt((DevOpsScript s) -> safe(s.getDownloadCount())).reversed())
                .limit(limit)
                .map(this::toAnalytics)
                .collect(Collectors.toList());
    }

    private ScriptAnalytics toAnalytics(DevOpsScript s) {
        return new ScriptAnalytics(
                s.getId(),
                s.getName(),
                safe(s.getViewCount()),
                safe(s.getDownloadCount()),
                safe(s.getCopyCount()),
                safe(s.getFavoriteCount()),
                s.getAverageRating() != null ? s.getAverageRating() : 0.0,
                safe(s.getRatingCount()));
    }

    // ------------------------------------------------------------------
    // Versioning
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ScriptVersion> getScriptVersions(Long scriptId) {
        requireScript(scriptId);
        return versionRepository.findByScriptIdOrderByReleasedAtDesc(scriptId);
    }

    @Override
    @Transactional(readOnly = true)
    public DevOpsScript getScriptVersion(Long scriptId, String version) {
        DevOpsScript script = requireScript(scriptId);
        initializeCollections(script);

        return versionRepository.findByScriptIdAndVersion(scriptId, version)
                .map(v -> copyWithVersion(script, v))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Version " + version + " not found for script " + scriptId));
    }

    /**
     * Build a detached copy of the script carrying the requested historical
     * version's code/version, without mutating the managed entity.
     */
    private DevOpsScript copyWithVersion(DevOpsScript script, ScriptVersion version) {
        DevOpsScript copy = new DevOpsScript();
        copy.setId(script.getId());
        copy.setName(script.getName());
        copy.setDescription(script.getDescription());
        copy.setCategory(script.getCategory());
        copy.setCode(version.getCode());
        copy.setUsageInstructions(script.getUsageInstructions());
        copy.setVersion(version.getVersion());
        copy.setTags(new ArrayList<>(script.getTags()));
        copy.setCloudProviders(new ArrayList<>(script.getCloudProviders()));
        copy.setDifficulty(script.getDifficulty());
        copy.setAuthor(script.getAuthor());
        copy.setAuthorId(script.getAuthorId());
        copy.setEstimatedTimeMinutes(script.getEstimatedTimeMinutes());
        copy.setPrerequisites(script.getPrerequisites());
        copy.setRelatedDocumentationUrl(script.getRelatedDocumentationUrl());
        copy.setStatus(script.getStatus());
        return copy;
    }

    // ------------------------------------------------------------------
    // Filtering / sorting / pagination helpers
    // ------------------------------------------------------------------

    private boolean matchesFilters(DevOpsScript script, ScriptFilters filters) {
        if (!filters.getCategories().isEmpty()
                && !containsIgnoreCase(filters.getCategories(), script.getCategory())) {
            return false;
        }
        if (!filters.getDifficulties().isEmpty()
                && !containsIgnoreCase(filters.getDifficulties(), script.getDifficulty())) {
            return false;
        }
        if (!filters.getCloudProviders().isEmpty()
                && !anyMatchIgnoreCase(filters.getCloudProviders(), script.getCloudProviders())) {
            return false;
        }
        if (!filters.getTags().isEmpty()
                && !anyMatchIgnoreCase(filters.getTags(), script.getTags())) {
            return false;
        }
        if (filters.getMinRating() != null) {
            double rating = script.getAverageRating() != null ? script.getAverageRating() : 0.0;
            if (rating < filters.getMinRating()) {
                return false;
            }
        }
        String query = filters.getSearchQuery();
        if (query != null && !query.trim().isEmpty()) {
            String q = query.trim().toLowerCase();
            boolean matches = contains(script.getName(), q)
                    || contains(script.getDescription(), q)
                    || (script.getTags() != null && script.getTags().stream()
                            .anyMatch(t -> contains(t, q)));
            if (!matches) {
                return false;
            }
        }
        return true;
    }

    private Comparator<DevOpsScript> comparatorFor(String sortBy) {
        String key = sortBy != null ? sortBy.toLowerCase() : "recent";
        switch (key) {
            case "popular":
                return Comparator.comparingInt((DevOpsScript s) -> safe(s.getDownloadCount())).reversed();
            case "rating":
                return Comparator.comparingDouble((DevOpsScript s) ->
                                s.getAverageRating() != null ? s.getAverageRating() : 0.0).reversed()
                        .thenComparing(Comparator.comparingInt((DevOpsScript s) -> safe(s.getRatingCount())).reversed());
            case "name":
                return Comparator.comparing(s -> s.getName() == null ? "" : s.getName().toLowerCase());
            case "recent":
            default:
                return Comparator.comparing(DevOpsScript::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        }
    }

    private List<DevOpsScript> paginate(List<DevOpsScript> items, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return new ArrayList<>(items);
        }
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), items.size());
        int to = (int) Math.min((long) from + pageable.getPageSize(), items.size());
        if (from >= to) {
            return new ArrayList<>();
        }
        return new ArrayList<>(items.subList(from, to));
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private DevOpsScript requireScript(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Script id must not be null");
        }
        return scriptRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("DevOps script not found: " + id));
    }

    private void recordInteraction(Long scriptId, String userId, String type) {
        interactionRepository.save(new UserScriptInteraction(userId, scriptId, type));
    }

    /** Force-initialize lazy element collections so cached/returned entities are complete. */
    private void initializeCollections(DevOpsScript script) {
        if (script == null) {
            return;
        }
        if (script.getTags() != null) {
            script.getTags().size();
        }
        if (script.getCloudProviders() != null) {
            script.getCloudProviders().size();
        }
    }

    private List<DevOpsScript> hydrateInOrder(List<Long> orderedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, DevOpsScript> byId = scriptRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(DevOpsScript::getId, s -> s));
        List<DevOpsScript> ordered = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            DevOpsScript s = byId.get(id);
            if (s != null) {
                ordered.add(s);
            }
        }
        return ordered;
    }

    private void safeIndex(DevOpsScript script) {
        try {
            searchService.indexScript(script);
        } catch (Exception e) {
            logger.warn("Failed to index script {} for search: {}", script.getId(), e.getMessage());
        }
    }

    private void safeDeleteFromIndex(Long scriptId) {
        try {
            searchService.deleteFromIndex(scriptId);
        } catch (Exception e) {
            logger.warn("Failed to delete script {} from search index: {}", scriptId, e.getMessage());
        }
    }

    private long secondsBetween(LocalDateTime from, LocalDateTime to) {
        return to.toEpochSecond(ZoneOffset.UTC) - from.toEpochSecond(ZoneOffset.UTC);
    }

    private int safe(Integer value) {
        return value != null ? value : 0;
    }

    private boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        return values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(candidate));
    }

    private boolean anyMatchIgnoreCase(List<String> wanted, List<String> actual) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        for (String w : wanted) {
            if (w == null) {
                continue;
            }
            for (String a : actual) {
                if (a != null && a.equalsIgnoreCase(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Cache helpers (fail-soft: cache problems never break the request)
    // ------------------------------------------------------------------

    private String listCacheKey(ScriptFilters filters, Pageable pageable) {
        String category = filters.getCategories().isEmpty() ? null
                : String.join(",", sortedLower(filters.getCategories()));
        String cloudProvider = filters.getCloudProviders().isEmpty() ? null
                : String.join(",", sortedLower(filters.getCloudProviders()));
        String difficulty = filters.getDifficulties().isEmpty() ? null
                : String.join(",", sortedLower(filters.getDifficulties()));

        String base;
        if (filters.getSearchQuery() != null && !filters.getSearchQuery().trim().isEmpty()) {
            base = keyGenerator.generateSearchKey(filters.getSearchQuery(), category, cloudProvider,
                    difficulty, filters.getTags(), filters.getSortBy());
        } else {
            base = keyGenerator.generateListKey(category, cloudProvider, difficulty,
                    filters.getMinRating(), filters.getSortBy());
        }

        int page = pageable != null ? pageable.getPageNumber() : 0;
        int size = pageable != null ? pageable.getPageSize() : 20;
        return base + ":fav=" + filters.isShowOnlyFavorites() + ":p=" + page + ":s=" + size;
    }

    private List<String> sortedLower(List<String> values) {
        return values.stream()
                .filter(v -> v != null)
                .map(String::toLowerCase)
                .sorted()
                .collect(Collectors.toList());
    }

    private Object cacheGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.debug("Cache get failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void cachePut(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            logger.debug("Cache put failed for key {}: {}", key, e.getMessage());
        }
    }
}
