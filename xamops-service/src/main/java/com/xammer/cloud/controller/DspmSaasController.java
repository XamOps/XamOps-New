package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.datastores.SaasDrawerHeader;
import com.xammer.cloud.dto.matters.datastores.SaasFileActivity;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasRecords;
import com.xammer.cloud.dto.matters.datastores.SaasScanSummary;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.SaasDrawerService;
import com.xammer.cloud.service.matters.SaasFileQuery;
import com.xammer.cloud.service.matters.SaasFileService;
import lombok.RequiredArgsConstructor;
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
 * The XamLens SaaS drawer (Gmail, Google Drive) and the SaaS file drawer stacked over it: 8 read-only
 * GETs over the Matters console routes (design 5.2, endpoints 1 to 7 and 17).
 *
 * <h2>Order of checks</h2>
 * Every method calls {@link XamLensTabGuard#require} first, then validates its input with
 * {@link DatastoreParams}, then calls a service with the principal's client id, so a 403 or a 400 never
 * reaches the vendor. Parameters are bound as a raw {@link MultiValueMap} and path variables as strings,
 * never through Spring's type conversion, so every validation failure has the same
 * {@code INVALID_REQUEST} body, and a parameter name an endpoint does not list is a 400 too.
 *
 * <h2>Tenancy</h2>
 * As in {@link DspmDatastoreController}, the client id comes from {@code @AuthenticationPrincipal} and
 * nowhere else. No endpoint has a {@code clientId} parameter, and sending one is a 400.
 *
 * <p>Datastore routes sit under {@code /api/dspm/datastores/{sourceId}/saas}, keyed by the row's
 * {@code sourceId}; file routes under {@code /api/dspm/saas-files/{fileId}}, because a file id is not
 * checked against a parent datastore (design 4.1). No path can match {@code DspmController}'s or
 * {@code DspmDatastoreController}'s. Failures are mapped by
 * {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmSaasController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> RECORDS_PARAMS = Set.of("view");
    private static final Set<String> FILES_PARAMS = Set.of("dataElement", "documentClass", "q", "skip", "limit");
    /** The file list's page size when {@code limit} is absent: the console's five rows. */
    private static final int FILES_DEFAULT_LIMIT = 5;

    private final XamLensTabGuard guard;
    private final SaasDrawerService drawerService;
    private final SaasFileService fileService;

    // ── SaaS drawer ──────────────────────────────────────────────────────────────────────────────

    @GetMapping("/datastores/{sourceId}/saas/detail")
    public ResponseEntity<SaasDrawerHeader> detail(@AuthenticationPrincipal ClientUserDetails user,
                                                   @PathVariable String sourceId,
                                                   @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.header(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    /** {@code view}: {@code data_elements} (default) or {@code document_classes}. */
    @GetMapping("/datastores/{sourceId}/saas/records")
    public ResponseEntity<SaasRecords> records(@AuthenticationPrincipal ClientUserDetails user,
                                               @PathVariable String sourceId,
                                               @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, RECORDS_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        String view = DatastoreParams.saasView(DatastoreParams.single(params, "view"));
        return ResponseEntity.ok(drawerService.records(user.getClientId(), id, view));
    }

    /**
     * Exactly one of {@code dataElement} and {@code documentClass} (a row name of the matching records
     * view), {@code q}, {@code skip} and {@code limit} (default 5).
     */
    @GetMapping("/datastores/{sourceId}/saas/files")
    public ResponseEntity<SaasFilePage> files(@AuthenticationPrincipal ClientUserDetails user,
                                              @PathVariable String sourceId,
                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, FILES_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        return ResponseEntity.ok(drawerService.files(user.getClientId(), id, fileQuery(params)));
    }

    @GetMapping("/datastores/{sourceId}/saas/scan-summary")
    public ResponseEntity<SaasScanSummary> scanSummary(@AuthenticationPrincipal ClientUserDetails user,
                                                       @PathVariable String sourceId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.scanSummary(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    // ── SaaS file drawer ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/saas-files/{fileId}/overview")
    public ResponseEntity<SaasFileOverview> fileOverview(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String fileId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(fileService.overview(user.getClientId(), DatastoreParams.fileId(fileId)));
    }

    @GetMapping("/saas-files/{fileId}/activity")
    public ResponseEntity<SaasFileActivity> fileActivity(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String fileId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(fileService.activity(user.getClientId(), DatastoreParams.fileId(fileId)));
    }

    /** The copies and, when there are any, the timeline of their content hash. */
    @GetMapping("/saas-files/{fileId}/lineage")
    public ResponseEntity<SaasFileLineage> fileLineage(@AuthenticationPrincipal ClientUserDetails user,
                                                       @PathVariable String fileId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(fileService.lineage(user.getClientId(), DatastoreParams.fileId(fileId)));
    }

    /** File Copies and Related Files: the copies other than the opened file. */
    @GetMapping("/saas-files/{fileId}/copies")
    public ResponseEntity<SaasFileCopies> fileCopies(@AuthenticationPrincipal ClientUserDetails user,
                                                     @PathVariable String fileId,
                                                     @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(fileService.copies(user.getClientId(), DatastoreParams.fileId(fileId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /** Neither or both of {@code dataElement} and {@code documentClass} is a 400 on {@code dataElement}. */
    private static SaasFileQuery fileQuery(MultiValueMap<String, String> params) {
        String dataElement = DatastoreParams.single(params, "dataElement");
        String documentClass = DatastoreParams.single(params, "documentClass");
        if ((dataElement == null) == (documentClass == null)) {
            throw new DspmBadRequestException("dataElement",
                    "exactly one of dataElement and documentClass is required");
        }
        SaasFileQuery.Kind kind = dataElement != null ? SaasFileQuery.Kind.DATA_ELEMENT
                : SaasFileQuery.Kind.DOCUMENT_CLASS;
        String value = dataElement != null
                ? DatastoreParams.requiredText("dataElement", dataElement)
                : DatastoreParams.requiredText("documentClass", documentClass);
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"), FILES_DEFAULT_LIMIT);
        return new SaasFileQuery(kind, value, search, skip, limit);
    }
}
