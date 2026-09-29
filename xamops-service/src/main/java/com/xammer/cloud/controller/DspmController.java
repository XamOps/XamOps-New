package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.DspmAssetDetail;
import com.xammer.cloud.dto.matters.DspmAssetRow;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmStatus;
import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.matters.DspmAssetQuery;
import com.xammer.cloud.service.matters.MattersDspmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-facing DSPM read API.
 *
 * <h2>Tenancy</h2>
 * Every method takes the client id from {@code @AuthenticationPrincipal} and from nowhere else. There is
 * deliberately no {@code clientId} request parameter anywhere in this controller: the resolved Matters
 * key is itself scoped to one project, so the client id chooses which customer's data is returned, and
 * accepting it from the caller would be a direct cross-tenant read.
 *
 * <p>This differs from {@code XamSecureController}, which accepts an {@code accountId} parameter and
 * validates ownership server-side. That works there because Upwind data is filtered after fetching. Here
 * there is nothing to validate against — the credential lookup <em>is</em> the authorisation — so the
 * parameter simply does not exist.
 *
 * <h2>Errors</h2>
 * Failures surface as {@code MattersApiException} and are translated by
 * {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}, which carries a stable
 * {@code code} for the frontend to branch on.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmController {

    /**
     * Filter dimensions accepted from the query string.
     *
     * Read explicitly rather than by binding the whole parameter map, so that {@code page},
     * {@code sort}, {@code q} and any future control parameter can never be mistaken for a filter
     * dimension. {@code DspmAssetQuery} applies its own allow-list too; this keeps the controller's
     * contract visible in one glance.
     */
    private static final List<String> FILTER_PARAMS = List.of(
            "provider", "resource_type", "severity", "scan_status", "sensitive_type",
            "integration_id", "data_element", "lifecycle");

    private final MattersDspmService dspmService;

    /**
     * Connection state. Never fails — the four unhappy states are values, not errors, because the page
     * renders a different explanation for each.
     */
    @GetMapping("/status")
    public ResponseEntity<DspmStatus> status(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(dspmService.getStatus(user.getClientId()));
    }

    /**
     * Headline counts and 14-day trends.
     *
     * @param category optional single tab. Omitted means the whole project, which is what the scan
     *                 coverage figures should reflect.
     */
    @GetMapping("/summary")
    public ResponseEntity<DspmSummary> summary(@AuthenticationPrincipal ClientUserDetails user,
                                               @RequestParam(required = false) String category) {
        return ResponseEntity.ok(dspmService.getSummary(user.getClientId(), category));
    }

    /**
     * The Cloud datastore list — cloud and on-prem together, matching the vendor console's Cloud screen.
     *
     * @param page 1-based, as the upstream numbers it.
     */
    @GetMapping("/cloud/assets")
    public ResponseEntity<DspmPage<DspmAssetRow>> cloudAssets(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam Map<String, String> allParams) {

        DspmAssetQuery query = DspmAssetQuery.cloud(q, sort, page, pageSize, extractFilters(allParams));
        return ResponseEntity.ok(dspmService.getCloudAssets(user.getClientId(), query));
    }

    /**
     * Filter options with faceted counts.
     *
     * Call this with the same filter parameters as the list. The upstream counts are disjunctive, so a
     * dimension the user has already selected from keeps showing all of its options rather than
     * collapsing to the selection.
     */
    @GetMapping("/cloud/filters")
    public ResponseEntity<DspmFilters> cloudFilters(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam(required = false) String q,
            @RequestParam Map<String, String> allParams) {

        DspmAssetQuery query = DspmAssetQuery.cloud(q, null, null, null, extractFilters(allParams));
        return ResponseEntity.ok(dspmService.getCloudFilters(user.getClientId(), query));
    }

    /**
     * One asset in full.
     *
     * An id from another customer's project, an unknown id and a malformed id are all answered
     * identically — the upstream returns 404 for each, and distinguishing them would leak the existence
     * of assets outside the caller's project.
     */
    @GetMapping("/assets/{assetId}")
    public ResponseEntity<DspmAssetDetail> asset(@AuthenticationPrincipal ClientUserDetails user,
                                                 @PathVariable String assetId) {
        return ResponseEntity.ok(dspmService.getAsset(user.getClientId(), assetId));
    }

    /**
     * Pulls the recognised filter dimensions out of the raw query map.
     *
     * Spring gives one value per key here even when a parameter is repeated; the comma-joined form is
     * handled by {@code DspmAssetQuery}, and the frontend sends that form, so nothing is lost.
     */
    private Map<String, List<String>> extractFilters(Map<String, String> allParams) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        for (String dimension : FILTER_PARAMS) {
            String value = allParams.get(dimension);
            if (value != null && !value.isBlank()) {
                filters.put(dimension, List.of(value));
            }
        }
        return filters;
    }
}
