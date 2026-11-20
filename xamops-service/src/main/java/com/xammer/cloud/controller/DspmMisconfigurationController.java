package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.MisconfigurationService;
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
 * The XamLens Misconfigurations page: 2 read-only GETs, the list and one policy's drawer, aggregated from
 * the per-datastore Matters {@code issues/overview} route (build 5, plan 1.4).
 *
 * <h2>Order of checks</h2>
 * Every method calls {@link XamLensTabGuard#require} first, then validates its input with
 * {@link DatastoreParams}, then calls the service with the principal's client id, so a 403 or a 400 never
 * reaches the vendor. Parameters are bound as a raw {@link MultiValueMap} and the path variable as a
 * string, never through Spring's type conversion; neither endpoint takes a parameter, so any is a 400.
 *
 * <h2>Tenancy</h2>
 * As in {@link DspmDatastoreController}, the client id comes from {@code @AuthenticationPrincipal} and
 * nowhere else. Sending {@code clientId} is a 400.
 *
 * <p>The {@code /policies/} segment keeps the drawer path at three segments under
 * {@code /api/dspm/misconfigurations}, so it can never match {@code DspmController}'s two-segment
 * routes. Failures are mapped by {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm/misconfigurations")
@RequiredArgsConstructor
public class DspmMisconfigurationController {

    private static final Set<String> NO_PARAMS = Set.of();

    private final XamLensTabGuard guard;
    private final MisconfigurationService service;

    /** The two cards and one row per policy. */
    @GetMapping
    public ResponseEntity<MisconfigurationList> list(@AuthenticationPrincipal ClientUserDetails user,
                                                     @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(service.list(user.getClientId()));
    }

    /** Details, Remediation and Affected Datastores of one policy; an unknown id is a 404. */
    @GetMapping("/policies/{policyId}")
    public ResponseEntity<MisconfigurationDetail> detail(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String policyId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(service.detail(user.getClientId(), DatastoreParams.policyId(policyId)));
    }
}
