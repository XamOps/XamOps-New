package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFileActivity;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.EndpointFileOverview;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileListPage;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.EndpointFileListQuery;
import com.xammer.cloud.service.matters.EndpointFileService;
import com.xammer.cloud.service.matters.FilesService;
import com.xammer.cloud.service.matters.SaasFileListQuery;
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
 * The XamLens Files page (SaaS and Endpoint tabs) and its Endpoint file drawer: 8 read-only GETs over the
 * Matters console routes (design 5.2, F1 to F8).
 *
 * <h2>Order of checks</h2>
 * As in {@link DspmSaasController}: every method calls {@link XamLensTabGuard#require} first, then
 * {@code requireAllowed}, then validates its input, then calls a service with the principal's client id, so a
 * 403 or a 400 never reaches the vendor. Parameters are bound as a raw {@link MultiValueMap} and path
 * variables as strings, and a parameter name an endpoint does not list ({@code clientId} included) is a 400.
 *
 * <p>The lists sit under {@code /api/dspm/files}; the drawer under {@code /api/dspm/endpoint-files/{fileId}},
 * keyed by the endpoint file's UUID, because a file is not checked against a parent datastore (design 4.1).
 * Failures are mapped by {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmFilesController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> SAAS_OPTIONS_PARAMS = Set.of("category");

    private final XamLensTabGuard guard;
    private final FilesService filesService;
    private final EndpointFileService endpointFileService;

    // ── Files page ───────────────────────────────────────────────────────────────────────────────

    /** F1: one keyset page of SaaS files. */
    @GetMapping("/files/saas")
    public ResponseEntity<SaasFileListPage> saasFiles(@AuthenticationPrincipal ClientUserDetails user,
                                                      @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SaasFileListQuery.PARAMETERS);
        SaasFileListQuery query = SaasFileListQuery.parse(params);
        return ResponseEntity.ok(filesService.saasFiles(user.getClientId(), query));
    }

    /** F2: the SaaS filters; {@code category} narrows Document Class to those categories' classes. */
    @GetMapping("/files/saas/filter-options")
    public ResponseEntity<DatastoreFilterOptions> saasFilterOptions(@AuthenticationPrincipal ClientUserDetails user,
                                                                    @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SAAS_OPTIONS_PARAMS);
        return ResponseEntity.ok(filesService.saasFilterOptions(user.getClientId(),
                SaasFileListQuery.categoryFilter(params)));
    }

    /** F3: one page of endpoint files across every agent. */
    @GetMapping("/files/endpoint")
    public ResponseEntity<EndpointFilePage> endpointFiles(@AuthenticationPrincipal ClientUserDetails user,
                                                          @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, EndpointFileListQuery.PARAMETERS);
        EndpointFileListQuery query = EndpointFileListQuery.parse(params);
        return ResponseEntity.ok(filesService.endpointFiles(user.getClientId(), query));
    }

    /** F4: the Endpoint filters. */
    @GetMapping("/files/endpoint/filter-options")
    public ResponseEntity<DatastoreFilterOptions> endpointFilterOptions(
            @AuthenticationPrincipal ClientUserDetails user, @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(filesService.endpointFilterOptions(user.getClientId()));
    }

    // ── Endpoint file drawer ─────────────────────────────────────────────────────────────────────

    /** F5: header, card, Sensitives and Details. */
    @GetMapping("/endpoint-files/{fileId}/overview")
    public ResponseEntity<EndpointFileOverview> overview(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String fileId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(endpointFileService.overview(user.getClientId(), fileId(fileId)));
    }

    /** F6: File Activity, the file's MIP tag history. */
    @GetMapping("/endpoint-files/{fileId}/activity")
    public ResponseEntity<EndpointFileActivity> activity(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String fileId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(endpointFileService.activity(user.getClientId(), fileId(fileId)));
    }

    /** F7: the file's own lane and its content-hash lineage. */
    @GetMapping("/endpoint-files/{fileId}/lineage")
    public ResponseEntity<EndpointFileLineage> lineage(@AuthenticationPrincipal ClientUserDetails user,
                                                       @PathVariable String fileId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(endpointFileService.lineage(user.getClientId(), fileId(fileId)));
    }

    /** F8: File Copies and Related Files. */
    @GetMapping("/endpoint-files/{fileId}/copies")
    public ResponseEntity<SaasFileCopies> copies(@AuthenticationPrincipal ClientUserDetails user,
                                                 @PathVariable String fileId,
                                                 @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(endpointFileService.copies(user.getClientId(), fileId(fileId)));
    }

    private static String fileId(String raw) {
        return DatastoreParams.uuid("fileId", raw);
    }
}
