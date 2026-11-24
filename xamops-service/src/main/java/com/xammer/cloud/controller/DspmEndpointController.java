package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOverview;
import com.xammer.cloud.dto.matters.datastores.EndpointScanPage;
import com.xammer.cloud.dto.matters.datastores.EndpointSensitiveMetrics;
import com.xammer.cloud.dto.matters.datastores.EndpointSummary;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.EndpointDrawerService;
import com.xammer.cloud.service.matters.EndpointFileQuery;
import com.xammer.cloud.service.matters.EndpointFilters;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * The XamLens Endpoint drawer: 7 read-only GETs over the Matters endpoint-agent console routes
 * (design 5.2, endpoints 8 to 14). {@code {sourceId}} is the row's 24-hex {@code source_id}, the agent
 * id.
 *
 * <h2>Order of checks</h2>
 * Every method calls {@link XamLensTabGuard#require} first, then validates its input with
 * {@link DatastoreParams} and {@link EndpointFilters}, then calls the service with the principal's
 * client id, so a 403 or a 400 never reaches the vendor. Parameters are bound as a raw
 * {@link MultiValueMap} and path variables as strings, never through Spring's type conversion, so every
 * validation failure has the same {@code INVALID_REQUEST} body, and a parameter name an endpoint does
 * not list is a 400 too. Only the four filters may repeat; their values are merged.
 *
 * <h2>Tenancy</h2>
 * As in {@link DspmDatastoreController}, the client id comes from {@code @AuthenticationPrincipal} and
 * nowhere else. No endpoint has a {@code clientId} parameter, and sending one is a 400.
 *
 * <p>Paths sit under {@code /api/dspm/datastores/{sourceId}/endpoint}, which no other DSPM controller
 * maps (design 4.1). Failures are mapped by
 * {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}; an endpoint-agent 5xx is a 502
 * after one request, never retried (design 4.8).
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmEndpointController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> SENSITIVES_PARAMS =
            Set.of("sensitives", "documentClass", "category", "dataElement", "showFalsePositives");
    private static final Set<String> OCCURRENCE_PARAMS = Set.of("entityName", "skip", "limit", "showFalsePositives");
    private static final Set<String> FILE_PARAMS =
            Set.of("sensitives", "documentClass", "category", "dataElement", "q", "skip", "limit");
    private static final Set<String> SCAN_PARAMS = Set.of("skip", "limit");

    private final XamLensTabGuard guard;
    private final EndpointDrawerService drawerService;

    @GetMapping("/datastores/{sourceId}/endpoint/overview")
    public ResponseEntity<EndpointOverview> overview(@AuthenticationPrincipal ClientUserDetails user,
                                                     @PathVariable String sourceId,
                                                     @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.overview(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/endpoint/filter-options")
    public ResponseEntity<DatastoreFilterOptions> filterOptions(@AuthenticationPrincipal ClientUserDetails user,
                                                                @PathVariable String sourceId,
                                                                @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.filterOptions(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    /** The Information tab's totals, unfiltered. */
    @GetMapping("/datastores/{sourceId}/endpoint/summary")
    public ResponseEntity<EndpointSummary> summary(@AuthenticationPrincipal ClientUserDetails user,
                                                   @PathVariable String sourceId,
                                                   @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.summary(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    /** The four filters, each repeatable and comma-separated, and {@code showFalsePositives}. */
    @GetMapping("/datastores/{sourceId}/endpoint/sensitives")
    public ResponseEntity<EndpointSensitiveMetrics> sensitives(@AuthenticationPrincipal ClientUserDetails user,
                                                               @PathVariable String sourceId,
                                                               @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SENSITIVES_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        EndpointFilters filters = EndpointFilters.parse(params);
        boolean showFalsePositives =
                DatastoreParams.showFalsePositives(DatastoreParams.single(params, "showFalsePositives"));
        return ResponseEntity.ok(drawerService.sensitives(user.getClientId(), id, filters, showFalsePositives));
    }

    /**
     * {@code entityName} (required, one of the agent's entities), {@code skip}, {@code limit} and
     * {@code showFalsePositives}. Carries masked samples, so the response is {@code Cache-Control: no-store}.
     */
    @GetMapping("/datastores/{sourceId}/endpoint/occurrences")
    public ResponseEntity<EndpointOccurrencePage> occurrences(@AuthenticationPrincipal ClientUserDetails user,
                                                              @PathVariable String sourceId,
                                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, OCCURRENCE_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        String entityName = DatastoreParams.entityName(DatastoreParams.single(params, "entityName"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"));
        boolean showFalsePositives =
                DatastoreParams.showFalsePositives(DatastoreParams.single(params, "showFalsePositives"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(drawerService.occurrences(user.getClientId(), id, entityName, skip, limit, showFalsePositives));
    }

    /** The four filters, {@code q}, {@code skip} and {@code limit} (default 10). */
    @GetMapping("/datastores/{sourceId}/endpoint/files")
    public ResponseEntity<EndpointFilePage> files(@AuthenticationPrincipal ClientUserDetails user,
                                                  @PathVariable String sourceId,
                                                  @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, FILE_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        EndpointFilters filters = EndpointFilters.parse(params);
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"));
        return ResponseEntity.ok(drawerService.files(user.getClientId(), id,
                new EndpointFileQuery(filters, search, skip, limit)));
    }

    /** {@code skip} and {@code limit} (default 10). */
    @GetMapping("/datastores/{sourceId}/endpoint/scans")
    public ResponseEntity<EndpointScanPage> scans(@AuthenticationPrincipal ClientUserDetails user,
                                                  @PathVariable String sourceId,
                                                  @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SCAN_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"));
        return ResponseEntity.ok(drawerService.scans(user.getClientId(), id, skip, limit));
    }
}
