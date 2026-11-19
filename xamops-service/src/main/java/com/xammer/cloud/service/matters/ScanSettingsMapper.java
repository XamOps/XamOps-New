package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.scans.DatastoreDatabases;
import com.xammer.cloud.dto.matters.scans.DatastoreScanSettings;
import com.xammer.cloud.dto.matters.scans.DatastoreSyncStatus;
import com.xammer.cloud.dto.matters.scans.EndpointScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasChannel;
import com.xammer.cloud.dto.matters.scans.SaasScanConfiguration;
import com.xammer.cloud.dto.matters.scans.SaasScanUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The whitelists of every route that returns configuration (design 4.4, 5.6.3): the Assists list, the SaaS
 * scan configuration, its users and channels, the endpoint agent configuration, a datastore's scan settings,
 * its databases and its sync status.
 *
 * <p>Each method builds its record field by field from the listed vendor keys only; nothing is copied
 * generically, and every text value passes {@link SettingsText#clean}. Never read (FR-SEC-3, design 5.6.5):
 * {@code uri}, {@code userName}, {@code password}, {@code dataProcessingUnits}, {@code kafka_topics},
 * {@code bucket_name}, {@code manifest_file_path}, {@code fileserver_url} and the other file-scan flags,
 * {@code full_scan_trigger}, {@code scan_schedule}, {@code scan_timeout}, {@code check_interval},
 * {@code auto_update}, {@code rules}, {@code exclude_rules}, {@code account_id}, {@code channel_id},
 * {@code created_at}, and any key not named here.
 */
@Component
public class ScanSettingsMapper {

    static final int MAX_ASSISTS = 500;
    static final int MAX_SAAS_SCOPES = 20;
    static final int MAX_DOMAINS = 200;
    static final int MAX_LIST_ROWS = 100;
    static final int MAX_PATHS = 200;
    static final int MAX_PATH_LENGTH = 1_024;
    static final int MAX_DATABASES = 1_000;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_NAME_LENGTH = 1_024;

    private static final Pattern HH_MM = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern IPV6 = Pattern.compile("^\\[?[0-9A-Fa-f:]{2,45}\\]?$");
    private static final Pattern SERVICE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern HEX_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final String ALL_DATABASES = "ALL";

    /**
     * {@link #toScanSettings}'s result: the DTO, and the two assist ids the service resolves to names. The
     * ids stay inside the backend and are never put in a DTO.
     */
    record Mapped(DatastoreScanSettings settings, String exportProxyId, String discoveryProxyId) {
    }

    // ── Assists ──────────────────────────────────────────────────────────────────────────────────

    /** {@code assists[]} [500]: id, proxy_name, client_id, health_status, assist_type, version, secret_expires_at. */
    public AssistList toAssists(JsonNode body) {
        List<AssistList.Assist> assists = new ArrayList<>();
        for (JsonNode node : body.path("assists")) {
            if (assists.size() >= MAX_ASSISTS) {
                break;
            }
            if (!node.isObject()) {
                continue;
            }
            String health = SettingsText.clean("assists.health_status", node.path("health_status"));
            String type = SettingsText.clean("assists.assist_type", node.path("assist_type"));
            assists.add(new AssistList.Assist(
                    SettingsText.clean("assists.id", node.path("id")),
                    SettingsText.clean("assists.proxy_name", node.path("proxy_name")),
                    SettingsText.clean("assists.client_id", node.path("client_id")),
                    health,
                    PageLabels.assistStatus(health),
                    type,
                    DspmLabels.prettify(type),
                    SettingsText.clean("assists.version", node.path("version")),
                    DrawerJson.epochSeconds(node.path("secret_expires_at"))));
        }
        return new AssistList(assists);
    }

    // ── SaaS scan configuration ──────────────────────────────────────────────────────────────────

    /** {@code configuration{…, scopes[]}}. */
    public SaasScanConfiguration toSaasConfiguration(JsonNode body) {
        JsonNode configuration = body.path("configuration");
        List<SaasScanConfiguration.Scope> scopes = new ArrayList<>();
        for (JsonNode scope : configuration.path("scopes")) {
            if (scopes.size() >= MAX_SAAS_SCOPES) {
                break;
            }
            if (!scope.isObject()) {
                continue;
            }
            String driveType = SettingsText.clean("scopes.drive_type", scope.path("drive_type"));
            scopes.add(new SaasScanConfiguration.Scope(
                    driveType,
                    DatastoreLabels.subtypeLabel(driveType),
                    SettingsText.clean("scopes.scope_type", scope.path("scope_type")),
                    DatastoreRowMapper.longOrNull(scope, "total_active_users"),
                    SettingsText.cleanAll("scopes.supported_domains", scope.path("supported_domains"),
                            MAX_DOMAINS, MAX_DOMAIN_LENGTH)));
        }
        Integer sampling = nonNegativeInt(configuration.path("sampling_percentage"));
        return new SaasScanConfiguration(
                SettingsText.clean("configuration.saas_integration_id", configuration.path("saas_integration_id")),
                flag(configuration, "is_realtime_enabled"),
                flag(configuration, "is_backfilling_enabled"),
                sampling == null || sampling > 100 ? null : sampling,
                flag(configuration, "is_provisioning_complete"),
                DrawerJson.epochSeconds(configuration.path("last_saved_at")),
                SettingsText.clean("configuration.last_saved_by", configuration.path("last_saved_by")),
                scopes);
    }

    /** {@code assets[]} [100] and {@code count}: id, email, status, is_selected. */
    public DspmPage<SaasScanUser> toSaasUsers(JsonNode body, int page, int pageSize) {
        List<SaasScanUser> users = new ArrayList<>();
        for (JsonNode node : body.path("assets")) {
            if (users.size() >= MAX_LIST_ROWS) {
                break;
            }
            if (!node.isObject()) {
                continue;
            }
            String status = SettingsText.clean("assets.status", node.path("status"));
            users.add(new SaasScanUser(
                    SettingsText.clean("assets.id", node.path("id")),
                    SettingsText.clean("assets.email", node.path("email")),
                    status,
                    PageLabels.saasUserStatus(status),
                    flag(node, "is_selected")));
        }
        return countedPage(users, body, page, pageSize);
    }

    /** {@code channels[]} [100] and {@code count}; {@code channel_id} and {@code created_at} are not read. */
    public DspmPage<SaasChannel> toSaasChannels(JsonNode body, int page, int pageSize) {
        List<SaasChannel> channels = new ArrayList<>();
        for (JsonNode node : body.path("channels")) {
            if (channels.size() >= MAX_LIST_ROWS) {
                break;
            }
            if (!node.isObject()) {
                continue;
            }
            channels.add(new SaasChannel(
                    SettingsText.clean("channels.id", node.path("id")),
                    SettingsText.clean("channels.name", node.path("name")),
                    SettingsText.clean("channels.channel_type", node.path("channel_type")),
                    SettingsText.clean("channels.channel_status", node.path("channel_status")),
                    DatastoreRowMapper.longOrNull(node, "member_count"),
                    flag(node, "is_external_shared"),
                    SettingsText.clean("channels.topic", node.path("topic")),
                    flag(node, "is_new"),
                    flag(node, "is_selected")));
        }
        return countedPage(channels, body, page, pageSize);
    }

    // ── Endpoint agent configuration ─────────────────────────────────────────────────────────────

    /** {@code data} (or {@code data.data}): scan mode, reconciliation window, paths and file types. */
    public EndpointScanConfiguration toEndpointConfiguration(JsonNode body) {
        JsonNode data = body.path("data");
        if (data.path("data").isObject()) {
            data = data.path("data");
        }
        if (!data.isObject() || data.isEmpty()) {
            return new EndpointScanConfiguration(false, null, null, null, List.of(), List.of(), List.of(), List.of());
        }
        return new EndpointScanConfiguration(
                true,
                SettingsText.clean("file_scan_config.scan_mode", data.path("file_scan_config").path("scan_mode")),
                time(SettingsText.clean("reconciliation_start_time", data.path("reconciliation_start_time"))),
                time(SettingsText.clean("reconciliation_end_time", data.path("reconciliation_end_time"))),
                SettingsText.cleanAll("scan_folder_paths", data.path("scan_folder_paths"), MAX_PATHS, MAX_PATH_LENGTH),
                SettingsText.cleanAll("excluded_folder_paths", data.path("excluded_folder_paths"), MAX_PATHS,
                        MAX_PATH_LENGTH),
                SettingsText.cleanAll("scan_target_type", data.path("scan_target_type"), MAX_PATHS, MAX_PATH_LENGTH),
                SettingsText.cleanAll("global_scan_target_types", data.path("global_scan_target_types"), MAX_PATHS,
                        MAX_PATH_LENGTH));
    }

    private static String time(String text) {
        return text != null && HH_MM.matcher(text).matches() ? text : null;
    }

    // ── datastore scan settings ──────────────────────────────────────────────────────────────────

    /**
     * {@code default_scan_parameters} and {@code selected_databases_list}. The assist ids are read as 24-hex
     * ids and returned beside the DTO, never in it.
     */
    Mapped toScanSettings(JsonNode body) {
        JsonNode p = body.path("default_scan_parameters");
        DatastoreScanSettings.Parameters parameters = new DatastoreScanSettings.Parameters(
                nonNegativeInt(p.path("numFoldersLeafNode")),
                nonNegativeInt(p.path("parallelism")),
                nonNegativeNumber(p.path("timeOut")),
                nonNegativeInt(p.path("rowSamplingLimit")),
                nonNegativeInt(p.path("samplingPercentageLimit")));

        JsonNode databases = body.path("selected_databases_list");
        boolean allDatabases = databases.isTextual() && ALL_DATABASES.equals(databases.textValue().trim());
        List<String> selected = databases.isArray()
                ? SettingsText.cleanAll("selected_databases_list", databases, MAX_DATABASES, MAX_NAME_LENGTH)
                : List.of();

        JsonNode accessible = p.path("isAccessible");
        DatastoreScanSettings settings = new DatastoreScanSettings(
                parameters,
                SettingsText.cleanAll("inclusionPaths", p.path("inclusionPaths"), MAX_PATHS, MAX_PATH_LENGTH),
                SettingsText.cleanAll("exclusionPaths", p.path("exclusionPaths"), MAX_PATHS, MAX_PATH_LENGTH),
                allDatabases,
                selected,
                accessible.isBoolean() ? accessible.booleanValue() : null,
                null,
                null,
                host(p.path("host")),
                port(p.path("port")),
                serviceName(SettingsText.clean("serviceName", p.path("serviceName"))),
                flag(p, "isSaved"));
        return new Mapped(settings, hexId(p.path("exportProxyId")), hexId(p.path("discoveryProxyId")));
    }

    /**
     * The host rule (design 5.6.3): a hostname or an IPv6 literal; anything with {@code @}, {@code :} outside
     * an IPv6 literal, {@code /}, {@code ?} or whitespace is dropped and logged with the field name.
     */
    private static String host(JsonNode node) {
        String text = SettingsText.clean("host", node);
        if (text == null) {
            return null;
        }
        if (HOSTNAME.matcher(text).matches() || IPV6.matcher(text).matches()) {
            return text;
        }
        SettingsText.logDrop("host");
        return null;
    }

    private static Integer port(JsonNode node) {
        Integer port = nonNegativeInt(node);
        if (port == null && node.isTextual()) {
            String text = node.textValue().trim();
            if (text.matches("^\\d{1,5}$")) {
                port = Integer.parseInt(text);
            }
        }
        return port != null && port >= 1 && port <= 65_535 ? port : null;
    }

    private static String serviceName(String text) {
        return text != null && SERVICE_NAME.matcher(text).matches() ? text : null;
    }

    private static String hexId(JsonNode node) {
        String text = DrawerJson.stringValue(node);
        return text != null && HEX_ID.matcher(text).matches() ? text.toLowerCase(Locale.ROOT) : null;
    }

    // ── databases and sync status ────────────────────────────────────────────────────────────────

    /** {@code databases_list[]} texts [1,000]. */
    public DatastoreDatabases toDatabases(JsonNode body) {
        return new DatastoreDatabases(SettingsText.cleanAll("databases_list", body.path("databases_list"),
                MAX_DATABASES, MAX_NAME_LENGTH));
    }

    /** {@code success} true and {@code response{status, updated_at}}, else no sync job. */
    public DatastoreSyncStatus toSyncStatus(JsonNode body) {
        JsonNode response = body.path("response");
        if (!flag(body, "success") || !response.isObject()) {
            return new DatastoreSyncStatus(null, null);
        }
        String status = SettingsText.clean("response.status", response.path("status"));
        return new DatastoreSyncStatus(status != null && UPPER_SNAKE.matcher(status).matches() ? status : null,
                DrawerJson.epochSeconds(response.path("updated_at")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static <T> DspmPage<T> countedPage(List<T> rows, JsonNode body, int page, int pageSize) {
        long total = DatastoreRowMapper.longOr(body, "count", rows.size());
        return new DspmPage<>(rows, page, pageSize, total, DatastoreRowMapper.totalPages(total, pageSize));
    }

    /** True only for a JSON {@code true}. */
    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() && value.booleanValue();
    }

    /** A whole number from 0 to Integer.MAX_VALUE (an integral double such as 4.0 included), else null. */
    private static Integer nonNegativeInt(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < 0 || value != Math.floor(value) || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private static Double nonNegativeNumber(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        return Double.isFinite(value) && value >= 0 ? value : null;
    }
}
