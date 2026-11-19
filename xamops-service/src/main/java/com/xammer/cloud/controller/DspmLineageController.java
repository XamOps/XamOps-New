package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.dto.matters.datastores.LineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.LineageFilePage;
import com.xammer.cloud.dto.matters.datastores.LineagePolicyPage;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.ExposureCategory;
import com.xammer.cloud.service.matters.LineageFilters;
import com.xammer.cloud.service.matters.LineageParams;
import com.xammer.cloud.service.matters.LineageRowQuery;
import com.xammer.cloud.service.matters.LineageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * The XamLens Data Lineage page: 6 read-only GETs (build 4 design 5.2, endpoints L1 to L6).
 *
 * <h2>Order of checks</h2>
 * Every method calls {@link XamLensTabGuard#require} first, then validates its input with
 * {@link DatastoreParams}, {@link LineageParams} and {@link LineageFilters}, then calls
 * {@link LineageService} with the principal's client id, so a 403 or a 400 never reaches the vendor and a
 * malformed request is a 400 whether the exposure switch is on or off. Parameters are bound as a raw
 * {@link MultiValueMap} and path variables as strings, so every validation failure has the same
 * {@code INVALID_REQUEST} body, and a parameter name an endpoint does not list is a 400 too.
 *
 * <h2>Tenancy</h2>
 * The client id comes from {@code @AuthenticationPrincipal} and nowhere else; sending {@code clientId} is
 * a 400. Failures are mapped by {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm/lineage")
@RequiredArgsConstructor
public class DspmLineageController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> FILTER_OPTIONS_PARAMS = Set.of("level");
    private static final Set<String> POLICIES_PARAMS = Set.of("category", "q", "skip", "limit",
            "severity", "severity_exclude", "datastoreName", "datastoreName_exclude", "label", "label_exclude");
    private static final Set<String> DATASTORES_PARAMS = Set.of("q", "skip", "limit", "severity", "severity_exclude");
    private static final Set<String> FILES_PARAMS = Set.of("skip", "limit");

    private final XamLensTabGuard guard;
    private final LineageService service;

    /** L1: step 0's category cards; {@code available: false} while the exposure switch is off. */
    @GetMapping("/categories")
    public ResponseEntity<LineageCategories> categories(@AuthenticationPrincipal ClientUserDetails user,
                                                        @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(service.categories(user.getClientId()));
    }

    /** L2: the More filters options; {@code level} is {@code policy} or {@code datastore}. */
    @GetMapping("/filter-options")
    public ResponseEntity<DatastoreFilterOptions> filterOptions(@AuthenticationPrincipal ClientUserDetails user,
                                                                @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, FILTER_OPTIONS_PARAMS);
        String level = LineageParams.level(DatastoreParams.single(params, "level"));
        return ResponseEntity.ok(service.filterOptions(user.getClientId(), level));
    }

    /** L3: one page of a category's policies, with {@code q}, paging and the three filters. */
    @GetMapping("/policies")
    public ResponseEntity<LineagePolicyPage> policies(@AuthenticationPrincipal ClientUserDetails user,
                                                      @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, POLICIES_PARAMS);
        ExposureCategory category = ExposureCategory.fromParam(DatastoreParams.single(params, "category"));
        LineageRowQuery query = rowQuery(params, LineageFilters.POLICY_DIMENSIONS);
        return ResponseEntity.ok(service.policies(user.getClientId(), category, query));
    }

    /** L4: one page of a policy's affected datastores, with {@code q}, paging and the Severity filter. */
    @GetMapping("/policies/{policyId}/datastores")
    public ResponseEntity<LineageDatastorePage> datastores(@AuthenticationPrincipal ClientUserDetails user,
                                                           @PathVariable String policyId,
                                                           @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, DATASTORES_PARAMS);
        String id = DatastoreParams.uuid("policyId", policyId);
        LineageRowQuery query = rowQuery(params, LineageFilters.DATASTORE_DIMENSIONS);
        return ResponseEntity.ok(service.datastores(user.getClientId(), id, query));
    }

    /** L5: one page of an exposure's affected files; {@code limit} 1..100, default 7. */
    @GetMapping("/datastores/{exposureId}/files")
    public ResponseEntity<LineageFilePage> files(@AuthenticationPrincipal ClientUserDetails user,
                                                 @PathVariable String exposureId,
                                                 @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, FILES_PARAMS);
        String id = LineageParams.exposureId(exposureId);
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = LineageParams.limit(DatastoreParams.single(params, "limit"), LineageParams.MAX_FILE_LIMIT,
                LineageParams.DEFAULT_LIMIT);
        return ResponseEntity.ok(service.files(user.getClientId(), id, skip, limit));
    }

    /** L6: the file's lanes and events; the file id is 24 hex or a UUID. */
    @GetMapping("/files/{fileId}/lineage")
    public ResponseEntity<SaasFileLineage> fileLineage(@AuthenticationPrincipal ClientUserDetails user,
                                                       @PathVariable String fileId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(service.fileLineage(user.getClientId(), LineageParams.fileId(fileId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** {@code q}, {@code skip} (default 0), {@code limit} (1..50, default 7) and the level's filters. */
    private static LineageRowQuery rowQuery(MultiValueMap<String, String> params, List<String> dimensions) {
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = LineageParams.limit(DatastoreParams.single(params, "limit"), LineageParams.MAX_ROW_LIMIT,
                LineageParams.DEFAULT_LIMIT);
        return new LineageRowQuery(search, skip, limit, LineageFilters.parse(params, dimensions));
    }
}
