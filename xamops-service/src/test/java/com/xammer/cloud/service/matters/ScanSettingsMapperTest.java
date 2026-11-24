package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.scans.DatastoreDatabases;
import com.xammer.cloud.dto.matters.scans.DatastoreScanSettings;
import com.xammer.cloud.dto.matters.scans.DatastoreSyncStatus;
import com.xammer.cloud.dto.matters.scans.EndpointScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasChannel;
import com.xammer.cloud.dto.matters.scans.SaasScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasScanUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6.3 on synthetic bodies shaped like the settings routes, and the AC19 planted markers: every
 * field that must never reach a DTO carries a unique marker, and the serialised DTO holds none of them.
 */
class ScanSettingsMapperTest {

    private static final String CLIENT_ID_56 = "QWxhZGRpbjpvcGVuIHNlc2FtZTEyMzQ1Njc4OTBhYmNkZWZnaGlqa2xt";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";
    private static final String EXPORT_ID = "feedfacefeedfacefeedface";
    private static final String DISCOVERY_ID = "0badc0de0badc0de0badc0de";

    /** One marker per never-read field (AC19). */
    private static final List<String> MARKERS = List.of(
            "MK_PASSWORD", "MK_USERNAME", "MK_URI", "MK_TOKEN", "MK_APIKEY", "MK_SECRET", "MK_FILESERVER",
            "MK_BUCKET", "MK_MANIFEST", "MK_ACCOUNT", "987654321", "MK_KAFKA", "MK_CHANNELID", "MK_CREATOR",
            "MK_REQUEST", EXPORT_ID, DISCOVERY_ID);

    /** The never-read fields, planted into any object of a body. */
    private static final String PLANTED = "'password': 'MK_PASSWORD', 'userName': 'MK_USERNAME',"
            + "'uri': 'postgres://u:MK_URI@h/db', 'token': 'MK_TOKEN', 'api_key': 'MK_APIKEY',"
            + "'secret': 'MK_SECRET', 'fileserver_url': 'https://MK_FILESERVER.example.test',"
            + "'bucket_name': 'MK_BUCKET', 'manifest_file_path': '/MK_MANIFEST.json', 'account_id': 'MK_ACCOUNT',"
            + "'dataProcessingUnits': 987654321, 'kafka_topics': ['MK_KAFKA'], 'channel_id': 'MK_CHANNELID',"
            + "'created_by': {'email': 'MK_CREATOR@example.test'}, 'full_scan_trigger': {'request_id': 'MK_REQUEST'}";

    private final ObjectMapper json = new ObjectMapper();
    private final ScanSettingsMapper mapper = new ScanSettingsMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertNoMarker(Object dto) {
        String text = serialise(dto);
        for (String marker : MARKERS) {
            assertThat(text).as("marker %s", marker).doesNotContain(marker);
        }
    }

    // ── bodies ───────────────────────────────────────────────────────────────────────────────────

    private static String assistsBody(String clientId) {
        return "{'success': true, " + PLANTED + ", 'assists': [{'id': 'as-1', 'client_id': '" + clientId + "',"
                + "'proxy_name': 'Office connector', 'health_status': 'INACTIVE', 'version': null,"
                + "'assist_type': 'DISCOVERY', 'secret_expires_at': 1800000000000, " + PLANTED + "}]}";
    }

    private static final String SAAS_CONFIGURATION = "{'success': true, " + PLANTED + ", 'configuration': {"
            + "'saas_integration_id': 'abcdef0123456789abcdef01', 'is_backfilling_enabled': true,"
            + "'is_realtime_enabled': false, 'sampling_percentage': 40, 'is_provisioning_complete': true,"
            + "'last_saved_at': 1700000000, 'last_saved_by': 'ann@example.test', " + PLANTED + ","
            + "'scopes': [{'drive_type': 'GOOGLE_WORKSPACE', 'scope_type': 'SELECTED_USERS', 'total_active_users': 9,"
            + "  'supported_domains': ['example.test', 'token=MK_TOKEN'], " + PLANTED + "},"
            + "  {'drive_type': 'GMAIL', 'scope_type': 'ALL_USERS', 'supported_domains': []}]}}";

    private static final String SAAS_USERS = "{'success': true, " + PLANTED + ", 'count': 31, 'assets': ["
            + "{'id': 'u1', 'email': 'ann@example.test', 'status': 'ACTIVE', 'is_selected': true, " + PLANTED + "},"
            + "{'id': 'u2', 'email': 'bo@example.test', 'status': 'SUSPENDED', 'is_selected': 'yes'}]}";

    private static final String SAAS_CHANNELS = "{'success': true, " + PLANTED + ", 'count': 3, 'channels': ["
            + "{'id': 'c1', 'name': 'general', 'channel_type': 'PUBLIC', 'channel_status': 'ACTIVE',"
            + " 'member_count': 12, 'is_external_shared': false, 'topic': 'Company news', 'created_at': 1700000000,"
            + " 'is_new': true, 'is_selected': false, " + PLANTED + "}]}";

    private static final String ENDPOINT = "{'success': true, " + PLANTED + ", 'data': {"
            + "'scan_folder_paths': ['/Users', '/Volumes/Share'], 'excluded_folder_paths': [],"
            + "'scan_schedule': {'value': 1.0, 'duration': 'WEEK'}, 'scan_target_type': ['pdf', 'docx'],"
            + "'scan_timeout': 30, 'check_interval': 60, 'auto_update': true,"
            + "'reconciliation_start_time': '01:00', 'reconciliation_end_time': '5am',"
            + "'file_scan_config': {'scan_mode': 'LOCAL', 'fileserver_url': 'https://MK_FILESERVER.example.test',"
            + "  'is_fileserver_url_configured': true, 'trust_all_cert': false},"
            + "'rules': ['MK_SECRET'], 'exclude_rules': ['MK_SECRET'],"
            + "'global_scan_target_types': ['pdf', 'docx', 'xlsx'], " + PLANTED + "}}";

    private static String scanSettingsBody(String host) {
        return "{'success': true, " + PLANTED + ", 'default_scan_parameters': {"
                + "'timeOut': 2.5, 'numFoldersLeafNode': 5, 'rowSamplingLimit': 1000, 'samplingPercentageLimit': 10,"
                + "'parallelism': 4, 'isSaved': true, 'isAccessible': true,"
                + "'inclusionPaths': ['/data'], 'exclusionPaths': ['/tmp', 'postgres://u:MK_URI@h/db'],"
                + "'host': '" + host + "', 'port': 5432, 'serviceName': 'ORCL.prod',"
                + "'exportProxyId': '" + EXPORT_ID + "', 'discoveryProxyId': '" + DISCOVERY_ID + "', "
                + PLANTED + "}, 'selected_databases_list': ['sales', 'hr']}";
    }

    private static final String DATABASES = "{'success': true, " + PLANTED
            + ", 'databases_list': ['analytics', 'apikey=MK_APIKEY', 'finance']}";

    private static final String SYNC = "{'success': true, " + PLANTED
            + ", 'response': {'status': 'IN_PROGRESS', 'updated_at': 1700000000000, " + PLANTED + "}}";

    // ── AC19 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC19: no planted marker reaches the DTO of any settings route")
    void plantedMarkers() {
        assertNoMarker(mapper.toAssists(parse(assistsBody(CLIENT_ID_56))));
        assertNoMarker(mapper.toSaasConfiguration(parse(SAAS_CONFIGURATION)));
        assertNoMarker(mapper.toSaasUsers(parse(SAAS_USERS), 1, 10));
        assertNoMarker(mapper.toSaasChannels(parse(SAAS_CHANNELS), 1, 10));
        assertNoMarker(mapper.toEndpointConfiguration(parse(ENDPOINT)));
        assertNoMarker(mapper.toScanSettings(parse(scanSettingsBody("db.example.internal"))).settings());
        assertNoMarker(mapper.toDatabases(parse(DATABASES)));
        assertNoMarker(mapper.toSyncStatus(parse(SYNC)));
    }

    // ── per route ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assists: fields and labels; a 56-character base64 client_id kept, a JWT dropped")
    void assists() {
        AssistList list = mapper.toAssists(parse(assistsBody(CLIENT_ID_56)));
        assertThat(list.assists()).containsExactly(new AssistList.Assist("as-1", "Office connector", CLIENT_ID_56,
                "INACTIVE", "Disabled", "DISCOVERY", "Discovery", null, 1_800_000_000L));

        assertThat(mapper.toAssists(parse(assistsBody(JWT))).assists().get(0).clientId()).isNull();
        assertThat(mapper.toAssists(parse("{'success': true}")).assists()).isEmpty();
    }

    @Test
    @DisplayName("SaaS configuration: flags, sampling, scopes with drive labels; a credential-shaped domain dropped")
    void saasConfiguration() {
        SaasScanConfiguration configuration = mapper.toSaasConfiguration(parse(SAAS_CONFIGURATION));
        assertThat(configuration.integrationId()).isEqualTo("abcdef0123456789abcdef01");
        assertThat(configuration.backfillingEnabled()).isTrue();
        assertThat(configuration.realtimeEnabled()).isFalse();
        assertThat(configuration.samplingPercentage()).isEqualTo(40);
        assertThat(configuration.provisioningComplete()).isTrue();
        assertThat(configuration.lastSavedAt()).isEqualTo(1_700_000_000L);
        assertThat(configuration.lastSavedBy()).isEqualTo("ann@example.test");
        assertThat(configuration.scopes()).containsExactly(
                new SaasScanConfiguration.Scope("GOOGLE_WORKSPACE", "Google Drive", "SELECTED_USERS", 9L,
                        List.of("example.test")),
                new SaasScanConfiguration.Scope("GMAIL", "Gmail", "ALL_USERS", null, List.of()));

        assertThat(mapper.toSaasConfiguration(parse("{'configuration': {'sampling_percentage': 140}}"))
                .samplingPercentage()).isNull();
        assertThat(mapper.toSaasConfiguration(parse("{}")).scopes()).isEmpty();
    }

    @Test
    @DisplayName("SaaS users and channels: rows, labels, flags and the vendor count as total")
    void saasUsersAndChannels() {
        DspmPage<SaasScanUser> users = mapper.toSaasUsers(parse(SAAS_USERS), 2, 10);
        assertThat(users.data()).containsExactly(
                new SaasScanUser("u1", "ann@example.test", "ACTIVE", "Active", true),
                new SaasScanUser("u2", "bo@example.test", "SUSPENDED", "Suspended", false));
        assertThat(users.page()).isEqualTo(2);
        assertThat(users.total()).isEqualTo(31);
        assertThat(users.totalPages()).isEqualTo(4);

        DspmPage<SaasChannel> channels = mapper.toSaasChannels(parse(SAAS_CHANNELS), 1, 10);
        assertThat(channels.data()).containsExactly(
                new SaasChannel("c1", "general", "PUBLIC", "ACTIVE", 12L, false, "Company news", true, false));
        assertThat(channels.total()).isEqualTo(3);
        assertThat(mapper.toSaasChannels(parse("{'channels': [{'id': 'c'}]}"), 1, 10).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("endpoint configuration: whitelisted fields, HH:MM times; no data is not configured")
    void endpointConfiguration() {
        EndpointScanConfiguration configuration = mapper.toEndpointConfiguration(parse(ENDPOINT));
        assertThat(configuration).isEqualTo(new EndpointScanConfiguration(true, "LOCAL", "01:00", null,
                List.of("/Users", "/Volumes/Share"), List.of(), List.of("pdf", "docx"), List.of("pdf", "docx", "xlsx")));

        assertThat(mapper.toEndpointConfiguration(parse("{'success': true, 'data': {'data': {"
                + "'file_scan_config': {'scan_mode': 'CLOUD'}}}}")).scanMode()).isEqualTo("CLOUD");
        assertThat(mapper.toEndpointConfiguration(parse("{'success': true}")).configured()).isFalse();
        assertThat(mapper.toEndpointConfiguration(parse("{'success': true, 'data': {}}")).configured()).isFalse();
    }

    @Test
    @DisplayName("scan settings: parameters, paths, databases, host, port, service name; assist ids beside the DTO")
    void scanSettings() {
        ScanSettingsMapper.Mapped mapped = mapper.toScanSettings(parse(scanSettingsBody("db.example.internal")));
        DatastoreScanSettings settings = mapped.settings();
        assertThat(settings.parameters()).isEqualTo(new DatastoreScanSettings.Parameters(5, 4, 2.5, 1000, 10));
        assertThat(settings.inclusionPaths()).containsExactly("/data");
        assertThat(settings.exclusionPaths()).containsExactly("/tmp");
        assertThat(settings.allDatabases()).isFalse();
        assertThat(settings.selectedDatabases()).containsExactly("sales", "hr");
        assertThat(settings.accessible()).isTrue();
        assertThat(settings.exportAssistName()).isNull();
        assertThat(settings.discoveryAssistName()).isNull();
        assertThat(settings.host()).isEqualTo("db.example.internal");
        assertThat(settings.port()).isEqualTo(5432);
        assertThat(settings.serviceName()).isEqualTo("ORCL.prod");
        assertThat(settings.credentialsSaved()).isTrue();
        assertThat(mapped.exportProxyId()).isEqualTo(EXPORT_ID);
        assertThat(mapped.discoveryProxyId()).isEqualTo(DISCOVERY_ID);
    }

    @Test
    @DisplayName("scan settings: the host rule keeps a hostname or IPv6 literal and drops anything else")
    void hostRule() {
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("[2001:db8::1]"))).settings().host())
                .isEqualTo("[2001:db8::1]");
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("10.0.0.5"))).settings().host()).isEqualTo("10.0.0.5");
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("user:pw@db"))).settings().host()).isNull();
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("db/x"))).settings().host()).isNull();
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("db?x=1"))).settings().host()).isNull();
        assertThat(mapper.toScanSettings(parse(scanSettingsBody("db host"))).settings().host()).isNull();
    }

    @Test
    @DisplayName("scan settings: ALL databases, the S3 fixture shape, and wrong types read as null")
    void scanSettingsShapes() {
        DatastoreScanSettings s3 = mapper.toScanSettings(parse("{'success': true, 'default_scan_parameters': {"
                + "'timeOut': 1.0, 'dataProcessingUnits': 987654321, 'numFoldersLeafNode': 3, 'rowSamplingLimit': 50,"
                + "'samplingPercentageLimit': 5, 'parallelism': 2, 'isSaved': false},"
                + "'selected_databases_list': 'ALL'}")).settings();
        assertThat(s3.allDatabases()).isTrue();
        assertThat(s3.selectedDatabases()).isEmpty();
        assertThat(s3.parameters()).isEqualTo(new DatastoreScanSettings.Parameters(3, 2, 1.0, 50, 5));
        assertThat(s3.accessible()).isNull();
        assertThat(s3.host()).isNull();
        assertThat(s3.credentialsSaved()).isFalse();
        assertNoMarker(s3);

        ScanSettingsMapper.Mapped odd = mapper.toScanSettings(parse("{'default_scan_parameters': {"
                + "'numFoldersLeafNode': -1, 'parallelism': 2.5, 'timeOut': 'x', 'port': 70000,"
                + "'serviceName': 'a b', 'exportProxyId': 'not-hex'}, 'selected_databases_list': 7}"));
        assertThat(odd.settings().parameters()).isEqualTo(new DatastoreScanSettings.Parameters(null, null, null,
                null, null));
        assertThat(odd.settings().port()).isNull();
        assertThat(odd.settings().serviceName()).isNull();
        assertThat(odd.settings().allDatabases()).isFalse();
        assertThat(odd.exportProxyId()).isNull();
        assertThat(odd.discoveryProxyId()).isNull();
    }

    @Test
    @DisplayName("databases: texts, a credential-shaped name dropped")
    void databases() {
        assertThat(mapper.toDatabases(parse(DATABASES))).isEqualTo(new DatastoreDatabases(List.of("analytics",
                "finance")));
        assertThat(mapper.toDatabases(parse("{}")).databases()).isEmpty();
    }

    @Test
    @DisplayName("sync status: a job's status and time; success:false or no response is no job")
    void syncStatus() {
        assertThat(mapper.toSyncStatus(parse(SYNC))).isEqualTo(new DatastoreSyncStatus("IN_PROGRESS", 1_700_000_000L));
        assertThat(mapper.toSyncStatus(parse("{'success': false, 'is_cooling_valid': false}")))
                .isEqualTo(new DatastoreSyncStatus(null, null));
        assertThat(mapper.toSyncStatus(parse("{'success': true}"))).isEqualTo(new DatastoreSyncStatus(null, null));
        assertThat(mapper.toSyncStatus(parse("{'success': true, 'response': {'status': 'lower case'}}")))
                .isEqualTo(new DatastoreSyncStatus(null, null));
    }
}
