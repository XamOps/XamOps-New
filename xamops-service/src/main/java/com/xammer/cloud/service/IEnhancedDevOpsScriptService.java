package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.ScriptRating;
import com.xammer.cloud.domain.ScriptVersion;
import com.xammer.cloud.dto.devops.CloudProvider;
import com.xammer.cloud.dto.devops.ScriptAnalytics;
import com.xammer.cloud.dto.devops.ScriptCreateRequest;
import com.xammer.cloud.dto.devops.ScriptFilters;
import com.xammer.cloud.dto.devops.ScriptUpdateRequest;
import com.xammer.cloud.dto.devops.SearchOptions;
import com.xammer.cloud.dto.devops.SearchResult;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Core service for managing DevOps scripts with advanced features including
 * tagging, ratings, versioning, multi-cloud support, search integration, user
 * interaction tracking and trending calculation.
 *
 * <p>Search is delegated to {@link IScriptSearchService} (PostgreSQL-backed) and
 * results/details are cached in Redis where appropriate.</p>
 *
 * Requirements: REQ-2.1.1 (Content Volume), REQ-2.1.3 (Categorization),
 *               REQ-2.3.2 (Advanced Filtering), REQ-2.4.1 (Ratings),
 *               REQ-2.4.2 (Favorites), REQ-2.4.3 (Usage Tracking),
 *               REQ-2.4.4 (Trending Scripts)
 */
public interface IEnhancedDevOpsScriptService {

    // ----- Core CRUD operations ----------------------------------------------

    /**
     * List published scripts matching the supplied filters, paginated.
     *
     * @param filters  filtering / sorting criteria (must not be null)
     * @param pageable pagination parameters
     * @return matching scripts for the requested page, in the requested sort order
     */
    List<DevOpsScript> getAllScripts(ScriptFilters filters, Pageable pageable);

    /**
     * Fetch a single script by id.
     *
     * @param id the script id
     * @return the script
     * @throws jakarta.persistence.EntityNotFoundException if no script exists
     */
    DevOpsScript getScriptById(Long id);

    /**
     * Create a new script, persist it and index it for search.
     */
    DevOpsScript createScript(ScriptCreateRequest request);

    /**
     * Apply a partial update to an existing script and re-index it.
     */
    DevOpsScript updateScript(Long id, ScriptUpdateRequest request);

    /**
     * Delete a script and remove it from the search index.
     */
    void deleteScript(Long id);

    // ----- Search and discovery ----------------------------------------------

    /**
     * Full-text + filtered search over scripts.
     */
    SearchResult<DevOpsScript> searchScripts(String query, SearchOptions options);

    /**
     * Scripts tagged with any of the supplied tags.
     */
    List<DevOpsScript> getScriptsByTags(List<String> tags);

    /**
     * Scripts supporting the given cloud provider.
     */
    List<DevOpsScript> getScriptsByCloudProvider(CloudProvider provider);

    /**
     * Up to {@code limit} trending scripts ranked by time-decayed recent activity.
     */
    List<DevOpsScript> getTrendingScripts(int limit);

    // ----- User interactions -------------------------------------------------

    /** Record a view interaction and increment the view metric. */
    void recordScriptView(Long scriptId, String userId);

    /** Record a download interaction and increment the download metric. */
    void recordScriptDownload(Long scriptId, String userId);

    /** Record a copy interaction and increment the copy metric. */
    void recordScriptCopy(Long scriptId, String userId);

    /**
     * Create or update the user's rating for a script and recompute the
     * script's average rating and rating count.
     *
     * @param rating star rating in the range [1,5]
     */
    ScriptRating rateScript(Long scriptId, String userId, int rating, String review);

    /** Mark a script as favorited by the user (idempotent). */
    void favoriteScript(Long scriptId, String userId);

    /** Remove the user's favorite for a script (idempotent). */
    void unfavoriteScript(Long scriptId, String userId);

    // ----- Analytics ---------------------------------------------------------

    /** Aggregated engagement metrics for a script. */
    ScriptAnalytics getScriptAnalytics(Long scriptId);

    /** Most popular published scripts by download count. */
    List<ScriptAnalytics> getPopularScripts(int limit);

    // ----- Versioning --------------------------------------------------------

    /** Version history for a script, newest first. */
    List<ScriptVersion> getScriptVersions(Long scriptId);

    /** A specific version of a script. */
    DevOpsScript getScriptVersion(Long scriptId, String version);
}
