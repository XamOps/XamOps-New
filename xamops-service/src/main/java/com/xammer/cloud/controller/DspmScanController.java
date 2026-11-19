package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.scans.DatastoreDatabases;
import com.xammer.cloud.dto.matters.scans.DatastoreScanSettings;
import com.xammer.cloud.dto.matters.scans.DatastoreSyncStatus;
import com.xammer.cloud.dto.matters.scans.EndpointScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasChannel;
import com.xammer.cloud.dto.matters.scans.SaasIntegrationList;
import com.xammer.cloud.dto.matters.scans.SaasScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasScanUser;
import com.xammer.cloud.dto.matters.scans.ScanRun;
import com.xammer.cloud.dto.matters.scans.ScanRunDatastore;
import com.xammer.cloud.dto.matters.scans.ScanRunDetail;
import com.xammer.cloud.dto.matters.scans.ScanSchedule;
import com.xammer.cloud.dto.matters.scans.ScanScheduleDetail;
import com.xammer.cloud.dto.matters.scans.ScanScope;
import com.xammer.cloud.dto.matters.scans.ScanScopeDetail;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.DspmBadRequestException;
import com.xammer.cloud.service.matters.SaasChannelQuery;
import com.xammer.cloud.service.matters.SaasUserQuery;
import com.xammer.cloud.service.matters.ScanConfigurationService;
import com.xammer.cloud.service.matters.ScanListQuery;
import com.xammer.cloud.service.matters.ScanManagementService;
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
 * Scan Management (build 6 design 5.2, endpoints S1 to S12) and the datastore drawer's scan extras (X1 to
 * X3): schedules, scan history, scopes, Scan Configurations, and a datastore's sync status, scan settings
 * and databases. All read-only GETs; every settings body passes the backend whitelists.
 *
 * <h2>Order of checks</h2>
 * As in {@link DspmSaasController}: {@link XamLensTabGuard#require} first, then {@link DatastoreParams}
 * validation over the raw parameters (an unlisted name, {@code clientId} included, is a 400, and so is a
 * filter of another list), then the service with the principal's client id, so a 403 or a 400 never
 * reaches the vendor. Path ids are bound as strings and validated here. Failures are mapped by
 * {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmScanController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> SCHEDULES_PARAMS =
            Set.of("q", "platform", "status", "coverage", "page", "pageSize");
    private static final Set<String> RUNS_PARAMS =
            Set.of("q", "coverage", "platform", "status", "trigger", "page", "pageSize");
    private static final Set<String> RUN_DATASTORES_PARAMS = Set.of("q", "account", "status", "page", "pageSize");
    private static final Set<String> SCOPES_PARAMS = Set.of("q", "platform", "page", "pageSize");
    private static final Set<String> USERS_PARAMS = Set.of("driveType", "q", "status", "domain", "page", "pageSize");
    private static final Set<String> CHANNELS_PARAMS =
            Set.of("q", "channelType", "channelStatus", "scanState", "isNew", "page", "pageSize");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "SUSPENDED");

    private final XamLensTabGuard guard;
    private final ScanManagementService scanService;
    private final ScanConfigurationService configurationService;

    // ── schedules ────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/scan-management/schedules")
    public ResponseEntity<DspmPage<ScanSchedule>> schedules(@AuthenticationPrincipal ClientUserDetails user,
                                                            @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SCHEDULES_PARAMS);
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.SCHEDULES, params);
        return ResponseEntity.ok(scanService.schedules(user.getClientId(), query));
    }

    @GetMapping("/scan-management/schedules/{scheduleId}")
    public ResponseEntity<ScanScheduleDetail> schedule(@AuthenticationPrincipal ClientUserDetails user,
                                                       @PathVariable String scheduleId,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        String id = DatastoreParams.scanObjectId("scheduleId", scheduleId);
        return ResponseEntity.ok(scanService.schedule(user.getClientId(), id));
    }

    // ── scan history ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/scan-management/scan-history")
    public ResponseEntity<DspmPage<ScanRun>> runs(@AuthenticationPrincipal ClientUserDetails user,
                                                  @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, RUNS_PARAMS);
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.RUNS, params);
        return ResponseEntity.ok(scanService.runs(user.getClientId(), query));
    }

    @GetMapping("/scan-management/scan-history/{runId}")
    public ResponseEntity<ScanRunDetail> run(@AuthenticationPrincipal ClientUserDetails user,
                                             @PathVariable String runId,
                                             @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        String id = DatastoreParams.scanObjectId("runId", runId);
        return ResponseEntity.ok(scanService.run(user.getClientId(), id));
    }

    @GetMapping("/scan-management/scan-history/{runId}/datastores")
    public ResponseEntity<DspmPage<ScanRunDatastore>> runDatastores(@AuthenticationPrincipal ClientUserDetails user,
                                                                    @PathVariable String runId,
                                                                    @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, RUN_DATASTORES_PARAMS);
        String id = DatastoreParams.scanObjectId("runId", runId);
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params);
        return ResponseEntity.ok(scanService.runDatastores(user.getClientId(), id, query));
    }

    // ── scopes ───────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/scan-management/scan-scopes")
    public ResponseEntity<DspmPage<ScanScope>> scopes(@AuthenticationPrincipal ClientUserDetails user,
                                                      @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, SCOPES_PARAMS);
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.SCOPES, params);
        return ResponseEntity.ok(scanService.scopes(user.getClientId(), query));
    }

    @GetMapping("/scan-management/scan-scopes/{scopeId}")
    public ResponseEntity<ScanScopeDetail> scope(@AuthenticationPrincipal ClientUserDetails user,
                                                 @PathVariable String scopeId,
                                                 @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        String id = DatastoreParams.scanObjectId("scopeId", scopeId);
        return ResponseEntity.ok(scanService.scope(user.getClientId(), id));
    }

    // ── Scan Configurations ──────────────────────────────────────────────────────────────────────

    @GetMapping("/scan-management/saas-integrations")
    public ResponseEntity<SaasIntegrationList> saasIntegrations(@AuthenticationPrincipal ClientUserDetails user,
                                                                @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(configurationService.saasIntegrations(user.getClientId()));
    }

    @GetMapping("/scan-management/saas-integrations/{integrationId}/configuration")
    public ResponseEntity<SaasScanConfiguration> saasConfiguration(@AuthenticationPrincipal ClientUserDetails user,
                                                                   @PathVariable String integrationId,
                                                                   @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        String id = DatastoreParams.integrationId(integrationId);
        return ResponseEntity.ok(configurationService.saasConfiguration(user.getClientId(), id));
    }

    /** {@code driveType} (required), {@code q}, {@code status} ACTIVE or SUSPENDED, {@code domain}, paging. */
    @GetMapping("/scan-management/saas-integrations/{integrationId}/users")
    public ResponseEntity<DspmPage<SaasScanUser>> saasUsers(@AuthenticationPrincipal ClientUserDetails user,
                                                            @PathVariable String integrationId,
                                                            @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, USERS_PARAMS);
        String id = DatastoreParams.integrationId(integrationId);
        String driveType = DatastoreParams.code("driveType", DatastoreParams.single(params, "driveType"));
        if (driveType == null) {
            throw new DspmBadRequestException("driveType", "driveType is required");
        }
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        String status = DatastoreParams.oneOf("status", DatastoreParams.single(params, "status"), USER_STATUSES);
        String domain = DatastoreParams.domain(DatastoreParams.single(params, "domain"));
        DatastoreParams.Paging paging = paging(params);
        SaasUserQuery query = new SaasUserQuery(driveType, search, status, domain, paging.page(), paging.pageSize());
        return ResponseEntity.ok(configurationService.saasUsers(user.getClientId(), id, query));
    }

    /** {@code q}, {@code channelType}, {@code channelStatus}, {@code scanState} (codes), {@code isNew}, paging. */
    @GetMapping("/scan-management/saas-integrations/{integrationId}/channels")
    public ResponseEntity<DspmPage<SaasChannel>> saasChannels(@AuthenticationPrincipal ClientUserDetails user,
                                                              @PathVariable String integrationId,
                                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, CHANNELS_PARAMS);
        String id = DatastoreParams.integrationId(integrationId);
        String search = DatastoreParams.search(DatastoreParams.single(params, "q"));
        String channelType = DatastoreParams.code("channelType", DatastoreParams.single(params, "channelType"));
        String channelStatus = DatastoreParams.code("channelStatus", DatastoreParams.single(params, "channelStatus"));
        String scanState = DatastoreParams.code("scanState", DatastoreParams.single(params, "scanState"));
        Boolean isNew = DatastoreParams.booleanFlag("isNew", DatastoreParams.single(params, "isNew"));
        DatastoreParams.Paging paging = paging(params);
        SaasChannelQuery query = new SaasChannelQuery(search, channelType, channelStatus, scanState, isNew,
                paging.page(), paging.pageSize());
        return ResponseEntity.ok(configurationService.saasChannels(user.getClientId(), id, query));
    }

    @GetMapping("/scan-management/endpoint-configuration")
    public ResponseEntity<EndpointScanConfiguration> endpointConfiguration(
            @AuthenticationPrincipal ClientUserDetails user, @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(configurationService.endpointConfiguration(user.getClientId()));
    }

    // ── datastore drawer extras ──────────────────────────────────────────────────────────────────

    @GetMapping("/datastores/{sourceId}/sync-status")
    public ResponseEntity<DatastoreSyncStatus> syncStatus(@AuthenticationPrincipal ClientUserDetails user,
                                                          @PathVariable String sourceId,
                                                          @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(configurationService.syncStatus(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/scan-settings")
    public ResponseEntity<DatastoreScanSettings> scanSettings(@AuthenticationPrincipal ClientUserDetails user,
                                                              @PathVariable String sourceId,
                                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(
                configurationService.scanSettings(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/scan-settings/databases")
    public ResponseEntity<DatastoreDatabases> databases(@AuthenticationPrincipal ClientUserDetails user,
                                                        @PathVariable String sourceId,
                                                        @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(configurationService.databases(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private static DatastoreParams.Paging paging(MultiValueMap<String, String> params) {
        return DatastoreParams.listPaging(DatastoreParams.single(params, "page"),
                DatastoreParams.single(params, "pageSize"));
    }
}
