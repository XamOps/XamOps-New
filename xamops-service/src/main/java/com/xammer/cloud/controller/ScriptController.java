package com.xammer.cloud.controller;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.domain.ScriptRating;
import com.xammer.cloud.dto.devops.RatingRequest;
import com.xammer.cloud.dto.devops.RatingResponse;
import com.xammer.cloud.dto.devops.ScriptFilters;
import com.xammer.cloud.dto.devops.SearchOptions;
import com.xammer.cloud.dto.devops.SearchResult;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.IEnhancedDevOpsScriptService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;

/**
 * REST API for browsing, searching and interacting with DevOps scripts.
 *
 * <p>All endpoints are rate limited to 100 requests/minute per user (see
 * {@code RateLimitInterceptor}) and validated with Bean Validation. Errors are
 * translated to meaningful HTTP responses by
 * {@code DevOpsScriptExceptionHandler}.</p>
 *
 * Requirements: REQ-2.1.1, REQ-2.3.1, REQ-2.3.2, REQ-2.4.1, REQ-2.4.2,
 *               REQ-2.4.3, REQ-2.8.1, REQ-2.8.2
 */
@RestController
@RequestMapping("/api/scripts")
@Validated
public class ScriptController {

    private final IEnhancedDevOpsScriptService scriptService;

    public ScriptController(IEnhancedDevOpsScriptService scriptService) {
        this.scriptService = scriptService;
    }

    // ----- Listing & details -------------------------------------------------

    /**
     * List published scripts matching the supplied filters, paginated.
     * GET /api/scripts
     */
    @GetMapping
    public ResponseEntity<List<DevOpsScript>> getScripts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) List<String> cloudProviders,
            @RequestParam(required = false) List<String> difficulties,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false, defaultValue = "recent") String sortBy,
            @RequestParam(required = false, defaultValue = "false") boolean showOnlyFavorites,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        ScriptFilters filters = buildFilters(search, categories, cloudProviders,
                difficulties, tags, minRating, sortBy, showOnlyFavorites);
        Pageable pageable = PageRequest.of(Math.max(0, page), size > 0 ? size : 20);

        List<DevOpsScript> scripts = scriptService.getAllScripts(filters, pageable);
        return ResponseEntity.ok(scripts);
    }

    /**
     * Fetch a single script by id.
     * GET /api/scripts/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<DevOpsScript> getScript(@PathVariable Long id) {
        return ResponseEntity.ok(scriptService.getScriptById(id));
    }

    // ----- Interactions ------------------------------------------------------

    /**
     * Record a view interaction.
     * POST /api/scripts/{id}/view
     */
    @PostMapping("/{id}/view")
    public ResponseEntity<Void> recordView(@PathVariable Long id,
                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        scriptService.recordScriptView(id, resolveUserId(userDetails));
        return ResponseEntity.ok().build();
    }

    /**
     * Record a download interaction.
     * POST /api/scripts/{id}/download
     */
    @PostMapping("/{id}/download")
    public ResponseEntity<Void> recordDownload(@PathVariable Long id,
                                               @AuthenticationPrincipal ClientUserDetails userDetails) {
        scriptService.recordScriptDownload(id, resolveUserId(userDetails));
        return ResponseEntity.ok().build();
    }

    /**
     * Record a copy-to-clipboard interaction.
     * POST /api/scripts/{id}/copy
     */
    @PostMapping("/{id}/copy")
    public ResponseEntity<Void> recordCopy(@PathVariable Long id,
                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        scriptService.recordScriptCopy(id, resolveUserId(userDetails));
        return ResponseEntity.ok().build();
    }

    /**
     * Create or update the caller's rating for a script.
     * POST /api/scripts/{id}/rate
     */
    @PostMapping("/{id}/rate")
    public ResponseEntity<RatingResponse> rateScript(
            @PathVariable Long id,
            @Valid @RequestBody RatingRequest request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        ScriptRating rating = scriptService.rateScript(
                id, resolveUserId(userDetails), request.getRating(), request.getReview());
        return ResponseEntity.ok(RatingResponse.from(rating));
    }

    /**
     * Favorite a script for the caller (idempotent).
     * POST /api/scripts/{id}/favorite
     */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Void> favorite(@PathVariable Long id,
                                         @AuthenticationPrincipal ClientUserDetails userDetails) {
        scriptService.favoriteScript(id, resolveUserId(userDetails));
        return ResponseEntity.ok().build();
    }

    /**
     * Remove the caller's favorite for a script (idempotent).
     * DELETE /api/scripts/{id}/favorite
     */
    @DeleteMapping("/{id}/favorite")
    public ResponseEntity<Void> unfavorite(@PathVariable Long id,
                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        scriptService.unfavoriteScript(id, resolveUserId(userDetails));
        return ResponseEntity.noContent().build();
    }

    // ----- Search & discovery ------------------------------------------------

    /**
     * Full-text + filtered search over scripts.
     * GET /api/scripts/search
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResult<DevOpsScript>> search(
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false) List<String> categories,
            @RequestParam(required = false) List<String> cloudProviders,
            @RequestParam(required = false) List<String> difficulties,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false, defaultValue = "recent") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        ScriptFilters filters = buildFilters(query, categories, cloudProviders,
                difficulties, tags, minRating, sortBy, false);
        SearchOptions options = new SearchOptions(filters, Math.max(0, page), size > 0 ? size : 20);

        SearchResult<DevOpsScript> result = scriptService.searchScripts(query, options);
        return ResponseEntity.ok(result);
    }

    /**
     * Trending scripts ranked by recent, time-decayed activity.
     * GET /api/scripts/trending
     */
    @GetMapping("/trending")
    public ResponseEntity<List<DevOpsScript>> trending(
            @RequestParam(defaultValue = "10") int limit) {
        int safeLimit = limit > 0 ? Math.min(limit, 50) : 10;
        return ResponseEntity.ok(scriptService.getTrendingScripts(safeLimit));
    }

    // ----- Helpers -----------------------------------------------------------

    private ScriptFilters buildFilters(String search, List<String> categories,
                                       List<String> cloudProviders, List<String> difficulties,
                                       List<String> tags, Double minRating, String sortBy,
                                       boolean showOnlyFavorites) {
        ScriptFilters filters = new ScriptFilters();
        filters.setSearchQuery(search);
        filters.setCategories(categories != null ? categories : new ArrayList<>());
        filters.setCloudProviders(cloudProviders != null ? cloudProviders : new ArrayList<>());
        filters.setDifficulties(difficulties != null ? difficulties : new ArrayList<>());
        filters.setTags(tags != null ? tags : new ArrayList<>());
        filters.setMinRating(minRating);
        filters.setSortBy(sortBy != null ? sortBy : "recent");
        filters.setShowOnlyFavorites(showOnlyFavorites);
        return filters;
    }

    /**
     * Resolve the authenticated user's stable identifier. The script services
     * key interactions, ratings and favorites by this string.
     */
    private String resolveUserId(ClientUserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Authentication is required for this operation");
        }
        return userDetails.getUsername();
    }
}
