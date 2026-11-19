package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.pages.DocumentClassList;
import com.xammer.cloud.dto.matters.pages.DspmDashboard;
import com.xammer.cloud.dto.matters.pages.RemediationActivity;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DashboardService;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.DspmPagesService;
import com.xammer.cloud.service.matters.RemediationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * The XamLens pages that are one resource each (build 6 design 5.2, endpoints P1 to P4): the Dashboard,
 * Document Classes, Assists and the Remediation activity. All read-only GETs.
 *
 * <h2>Order of checks</h2>
 * As in {@link DspmSaasController}: {@link XamLensTabGuard#require} first, then {@link DatastoreParams}
 * validation over the raw parameters (an unlisted name, {@code clientId} included, is a 400), then the
 * service with the principal's client id, so a 403 or a 400 never reaches the vendor. Failures are mapped
 * by {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmPagesController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> REMEDIATION_PARAMS = Set.of("q", "status", "actionType", "skip", "limit");
    /** The Remediation table's page size when {@code limit} is absent. */
    private static final int REMEDIATION_DEFAULT_LIMIT = 10;

    private final XamLensTabGuard guard;
    private final DashboardService dashboardService;
    private final DspmPagesService pagesService;

    @GetMapping("/dashboard")
    public ResponseEntity<DspmDashboard> dashboard(@AuthenticationPrincipal ClientUserDetails user,
                                                   @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dashboardService.dashboard(user.getClientId()));
    }

    @GetMapping("/document-classes")
    public ResponseEntity<DocumentClassList> documentClasses(@AuthenticationPrincipal ClientUserDetails user,
                                                             @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(pagesService.documentClasses(user.getClientId()));
    }

    @GetMapping("/assists")
    public ResponseEntity<AssistList> assists(@AuthenticationPrincipal ClientUserDetails user,
                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(pagesService.assists(user.getClientId()));
    }

    /** {@code q}, {@code status} and {@code actionType} (upper-snake codes), {@code skip}, {@code limit} (default 10). */
    @GetMapping("/remediation/activity")
    public ResponseEntity<RemediationActivity> remediation(@AuthenticationPrincipal ClientUserDetails user,
                                                           @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, REMEDIATION_PARAMS);
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        String status = DatastoreParams.code("status", DatastoreParams.single(params, "status"));
        String actionType = DatastoreParams.code("actionType", DatastoreParams.single(params, "actionType"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"), REMEDIATION_DEFAULT_LIMIT);
        return ResponseEntity.ok(pagesService.remediation(user.getClientId(),
                new RemediationQuery(search, status, actionType, skip, limit)));
    }
}
