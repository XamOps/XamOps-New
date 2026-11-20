package com.xammer.cloud.service.matters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.controller.DspmDatastoreController;
import com.xammer.cloud.controller.DspmEndpointController;
import com.xammer.cloud.controller.DspmFilesController;
import com.xammer.cloud.controller.DspmSaasController;
import com.xammer.cloud.controller.advice.MattersExceptionHandler;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.exception.GlobalExceptionHandler;
import com.xammer.cloud.repository.GlobalTabRuleReader;
import com.xammer.cloud.repository.UserTabAccessRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Build 9 planted-marker test: every vendor sample field answers {@link #RAW}; every XamLens endpoint of the
 * Datastores, Endpoint, SaaS and Files controllers (the datastores CSV export reads the list route) is called
 * as ROLE_XAMOPS and as ROLE_SUPER_ADMIN, through the real controllers, services, mappers and an in-memory
 * cache. The raw marker must appear in no response, cache key, cached value or log event; the two
 * occurrence routes carry it masked by {@link SampleMask}.
 */
class XamLensSampleMaskingTest {
    private static final String RAW = "<<PLANTED-RAW-9Q4Z-SAMPLE>>";
    private static final String RAW_CORE = "PLANTED-RAW";
    private static final String HALF = "<<PLA••••-R••-9Q••-SA••••>>";
    private static final String SECRET = "<<PLAN•••••••••••••••••••>>";
    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String DATASTORE = "/api/dspm/datastores/" + SOURCE_ID;
    private static final String ENDPOINT = DATASTORE + "/endpoint";
    private static final String SAAS = DATASTORE + "/saas";
    private static final String SAAS_FILE_ID = "abcdef0123456789abcdef01";
    private static final String SAAS_FILE = "/api/dspm/saas-files/" + SAAS_FILE_ID;
    private static final String ENDPOINT_FILE = "/api/dspm/endpoint-files/0000aaaa-0000-4000-8000-00000000000c";
    private static final String FILES_SAAS = "/api/dspm/files/saas";
    private static final String FILES_ENDPOINT = "/api/dspm/files/endpoint";
    private static final String OWNER = "0a0b0c0d0e0f101112131415";
    private static final String DATA_HASH = "0f".repeat(32);
    private static final String TAB = "secops-xamlens";

    // ── planted vendor bodies: RAW in every sample field, invented values elsewhere ─────────────────
    private static final String LIST_ASSETS = """
            {"data":[
              {"id":"asset-1","source_id":"%2$s","name":"store-a","category":"cloud",
               "subtype":"S3","risk":{"level":"HIGH","score":7.5},
               "sensitives":{"record_count":12,"types":["PII"],"samples":["%1$s"],"sampled_data":[{"value":"%1$s"}]},
               "source":{"account":"000000000000000000000001","provider":"S3","region":"eu-west-1"},
               "scan_status":{"status":"CLASSIFIED","last_scanned_at":1700000000},
               "sampled_data":[{"value":"%1$s"}]}],
             "pagination":{"page":1,"page_size":50,"total":1}}
            """.formatted(RAW, SOURCE_ID);
    private static final String LIST_FILTERS = """
            {"integration_id":[{"value":"000000000000000000000001","count":4}],
             "provider":[{"value":"S3","count":4}],
             "scan_status":[{"value":"CLASSIFIED","count":3}],
             "lifecycle":[{"value":"ACTIVE","count":4}],
             "paging":{"integration_id":{"has_more":false}}}
            """;
    private static final String SUMMARY = """
            {"cards":{"by_status":{"CLASSIFIED":20},"critical_high":{"value":4,"trend":[]},
              "total_assets":{"value":31,"trend":[]},"total_sensitive_records":{"value":900,"trend":[]}},
             "tabs":{"all":{"value":31,"trend":[],"total_sensitive_records":{"value":900,"trend":[]},
                            "critical_high":{"value":4}},
                     "cloud":{"value":12,"trend":[],"total_sensitive_records":{"value":500,"trend":[]},
                              "critical_high":{"value":3}}}}
            """;
    private static final String INVENTORY_OVERVIEW = """
            {"success":true,"inventories_details":{
              "scanning_status":"SCAN_STOPPED","canDiscoverSchema":false,
              "asset_details":{"url":"https://console.example.test/store-a","entitySamples":[{"value":"%1$s"}],
                               "sampled_data":[{"value":"%1$s"}]},
              "file_types":{"pdf_files":2},"total_files":2,
              "sensitive_records":{"sensitive_records_count":5,"sampled_data":[{"value":"%1$s"}],
                                   "label_wise_sensitive_records_count":{"PII":[{"entity_name":"PERSON NAME","count":5,
                                       "sampled_data":[{"value":"%1$s"}]}]}},
              "properties":{"region":"eu-west-1","datastore_size":1024},
              "security_configurations":{"default_encryption":"ENABLED"}}}
            """.formatted(RAW);
    private static final String INVENTORY_SENSITIVES = """
            {"success":true,
             "entity_level_response":[{"entity_name":"PERSON NAME","count":5,"labels":["PII"],
                                       "sampled_data":[{"value":"%1$s"}]},
                                      {"entity_name":"ACCESS KEY AWS","count":1,"labels":["SECRETS"],
                                       "sampled_data":[{"value":"%1$s"}]}],
             "inventories_sensitives":[{"path":"s3a://store-a/reports/a.csv","sampled_data":[{"value":"%1$s"}],
                                        "found_in_records":["%1$s"]}],
             "file_level_sensitives_count":{
               "s3a://store-a/reports/a.csv":{"PERSON NAME":{"entityCount":5,"labels":["PII"],"columnName":"name",
                   "reason":"%1$s","found_in_records":["%1$s"],"sampled_data":[{"value":"%1$s"}]},
                                              "ACCESS KEY AWS":{"entityCount":1,"labels":["SECRETS"],
                   "reason":"%1$s","sampled_data":[{"value":"%1$s"}]}}}}
            """.formatted(RAW);
    private static final String INVENTORY_ENTITY = """
            {"success":true,"entity_level_response":{"entity_name":"%2$s","total_sensitive_occurrences":5,
              "labels":["%3$s"],
              "data":[{"url":"s3a://store-a/reports/a.csv","is_false_positive":false,
                       "sampled_data":[{"value":"%1$s"}]},
                      {"database_name":"crm","schema_name":"public","table_name":"users","column_name":"name",
                       "sampled_data":[{"value":"%1$s"}]}],
              "pagination":{"total":2,"skip":0,"limit":80}}}
            """;
    private static final String INVENTORY_ISSUES = """
            {"success":true,"risk":[{"risk":"Bucket versioning is disabled","source":"upwind","severity":"medium",
              "description":"## Why","remediation":"Enable it","remediation_recommendation_code_cli":"aws s3api x",
              "details":{"sampled_data":[{"value":"%1$s"}]}}]}
            """.formatted(RAW);
    private static final String ACCESS_GRAPH = """
            {"success":true,"access_graph":{"type":"S3","name":"store-a","children":{
              "roles":[{"name":"role-a","read":true,"list":true}],"users":[],"groups":[]}}}
            """;
    private static final String SCAN_STATUS = "{\"success\":true,\"scan_progress\":42.5}";
    private static final String SCAN_HISTORY = """
            {"success":true,"scan_history":[{"id":"run-1","execution_number":1,"start_time":1700000000,
              "count_of_data_at_risk":5,"sensitivity_level":"CRITICAL_SENSITIVITY",
              "triggered_by":"user@example.com","status":"SCAN_STOPPED"}]}
            """;
    /** Schema routes: one item per page, with sample-shaped fields a vendor could add. */
    private static final String SCHEMA_PAGE = """
            {"data":[{"id":"%2$s","name":"item_1","engine":"postgres","row_count_estimate":3,"foreign_key_count":1,
                      "sensitive_count":1,"native_type":"varchar","data_elements":["PERSON NAME"],
                      "samples":["%1$s"],"sample_values":["%1$s"],"sampled_data":[{"value":"%1$s"}]}],
             "pagination":{"page":1,"page_size":100,"total":1,"total_pages":1}}
            """.formatted(RAW, UUID);
    private static final String ERD = """
            {"nodes":[{"id":"t-1","name":"invoice","sensitive_count":2,"row_count_estimate":9,"samples":["%1$s"]},
                      {"id":"t-2","name":"client"}],
             "edges":[{"source_table_id":"t-1","target_table_id":"t-2",
                       "columns":[{"source_column_name":"client_id","target_column_name":"id",
                                   "sample_values":["%1$s"]}]}]}
            """.formatted(RAW);
    private static final String RELATIONS = """
            {"outgoing":[{"other_schema_name":"public","other_table_name":"client",
                          "columns":[{"source_column_name":"client_id","target_column_name":"id",
                                      "sample_values":["%1$s"]}]}],
             "incoming":[]}
            """.formatted(RAW);
    private static final String AGENT_OVERVIEW = """
            {"success":true,"data":{"device_serial_number":"SER-0001","agent_status":"ACTIVE",
              "hostname":"Robin Laptop","users":["Robin"],"sampled_data":["%1$s"]}}
            """.formatted(RAW);
    private static final String AGENT_FILTER_OPTIONS = """
            {"success":true,"data":{"sensitives":["PII"],"subcategories":["system documentation"],
              "categories":["it & technical"],"dataElements":["EMAIL ADDRESS"]}}
            """;
    private static final String AGENT_SENSITIVE_FILES = """
            {"success":true,"data":{"total_files":12,"extension_counts":{"pdf":2},
              "label_wise_sensitive_entities":{"PII":[{"entity_name":"EMAIL ADDRESS","count":4}]},
              "category_subcategory_counts":{"it & technical":{"system documentation":3}},
              "total_sensitives_count":6,
              "sampleMatches":[{"name":"EMAIL ADDRESS","label":"PII","samples":[{"match_text":"%1$s"}]}],
              "sampled_data":["%1$s"],
              "unified_metrics":[
                {"label":"PII","entity_name":"EMAIL ADDRESS","category":"","subcategory":"","count":4},
                {"label":"","entity_name":"","category":"it & technical","subcategory":"system documentation",
                 "count":3}]}}
            """.formatted(RAW);
    private static final String AGENT_ENTITY = """
            {"success":true,"data":[{"name":"notes.txt","path":"/Users/synthetic/notes.txt","tags":[],
              "isFalsePositive":false,
              "sampleMatches":[{"name":"EMAIL ADDRESS","label":"PII","samples":[{"match_text":"%1$s"}]}]}],
             "total":1}
            """.formatted(RAW);
    private static final String AGENT_FILES = """
            {"success":true,"data":[{"name":"plan.md","extension":"md","path":"/Users/synthetic/plan.md",
              "sensitiveEntitiesCount":1,"labels":["PII"],"category":"it & technical",
              "sampleMatches":[{"name":"EMAIL ADDRESS","label":"PII","samples":[{"match_text":"%1$s"}]}],
              "sensitiveEntities":[{"name":"EMAIL ADDRESS","samples":["%1$s"]}]}],
             "total":1}
            """.formatted(RAW);
    private static final String AGENT_SCANS = """
            {"success":true,"data":[{"status":"completed","start_time":1789725004,"end_time":1789725300,
              "sensitive_files":3,"total_files":10,"sampled_data":["%1$s"]}],"count":1}
            """.formatted(RAW);
    private static final String SAAS_DETAIL = """
            {"success":true,"detail":{"drive_type":"gmail","account_name":"ops-mailbox","owner":"Robin Vale",
              "scan_state":"COMPLETED","realtime_scan_status":"ENABLED","sampled_data":["%1$s"]}}
            """.formatted(RAW);
    private static final String SAAS_ELEMENT_RECORDS = """
            {"success":true,"view":"data_elements","records":[
              {"sensitive_record":"EMAIL ADDRESS","label":["PII"],"count":40,"sampled_data":["%1$s"],
               "samples":["%1$s"]}]}
            """.formatted(RAW);
    private static final String SAAS_CLASS_RECORDS = """
            {"success":true,"view":"document_classes","records":[
              {"category_label":"Financial","subcategory_label":"Budget Plans","count":8,"sample":"%1$s",
               "sampled_data":["%1$s"]}]}
            """.formatted(RAW);
    private static final String SAAS_FILES = """
            {"success":true,"files":[
              {"id":"ABCDEF0123456789ABCDEF01","file":"Plan Draft.pdf","records":4,"sampled_data":"%1$s",
               "samples":["%1$s"]}],
             "count":1}
            """.formatted(RAW);
    private static final String SAAS_SCAN_SUMMARY = """
            {"success":true,"scan_summary":{"supported":{"count":10,"size":2048},"unsupported":{"count":1},
              "file_type_breakdown":{"pdf":3},"sampled_data":["%1$s"]}}
            """.formatted(RAW);
    private static final String SAAS_FILE_OVERVIEW = """
            {"success":true,"overview":{"name":"Plan Draft.pdf","file_extension":"PDF",
              "owner":"robin@example.test","metadata":{"sizeEstimate":1024,"headers":[{"value":"%1$s"}]},
              "sampled_data":[{"value":"%1$s"}],
              "sensitives":[{"data_element":"EMAIL ADDRESS","label":["PII"],"count":2,"samples":["%1$s"],
                             "sampled_data":[{"value":"%1$s"}]}]}}
            """.formatted(RAW);
    private static final String REMEDIATION_LOGS = """
            {"success":true,"response":[{"title":"FILE_SHARED","status":"SUCCESS",
              "initiated_by":"robin@example.test","created_at":1789725004,"sampled_data":["%1$s"]}]}
            """.formatted(RAW);
    private static final String COPIES = """
            {"success":true,"data":{"data_hash":"%2$s","known_copies":1,"truncated":false,
              "origin":{"data_object_id":"do-1","file_id":"%3$s","name":"Plan Draft.pdf"},
              "copies":[
                {"data_object_id":"do-1","file_id":"%3$s","source":"SAAS","name":"Plan Draft.pdf",
                 "last_modified_by":"robin@example.test","created_at":"2026-09-10T09:15:00Z",
                 "sampled_data":["%1$s"],
                 "lane":{"resource_id":"res-1","provider":"GOOGLE_WORKSPACE","display_name":"Robin Vale",
                         "external_id":"robin@example.test","legacy_id":"aaaaaaaaaaaaaaaaaaaaaaf8",
                         "drive_type":"GMAIL"}}]}}
            """.formatted(RAW, DATA_HASH, SAAS_FILE_ID.toUpperCase());
    private static final String TIMELINE = """
            {"success":true,"data":{"data_hash":"%2$s",
              "lanes":[{"resource_id":"res-1","data_object_id":"do-1"}],
              "events":[{"data_object_id":"do-1","type":"FILE_SHARED","actor":"sam@example.test",
                         "timestamp":"2026-09-12T10:00:00Z","sampled_data":["%1$s"]}]}}
            """.formatted(RAW, DATA_HASH);
    private static final String SAAS_CATEGORIES = """
            {"success":true,"categories":[{"category":"financial","subcategories":["travel receipts"]}]}
            """;
    private static final String SAAS_USERS = """
            {"success":true,"owners":[{"key":"robin@example.test","value":"1a1b1c1d1e1f202122232425"}],
             "internal_users":["sam@example.test"],"external_users":[]}
            """;
    private static final String SAAS_SENSITIVITIES = """
            {"success":true,"sensitives":[{"id":"0a0b0c0d0e0f101112131415","file_name":"Plan Draft.pdf",
              "source":"GOOGLE_WORKSPACE","extension":"pdf","category":"financial",
              "sampled_data":[{"value":"%1$s"}],"samples":["%1$s"]}],
             "next_cursor":"eyJuZXh0IjoyfQ"}
            """.formatted(RAW);
    private static final String ENDPOINT_FILES = """
            {"success":true,"data":[{"id":"0000AAAA-0000-4000-8000-00000000000C","name":"notes.txt",
              "sampleMatches":[{"name":"EMAIL ADDRESS","samples":[{"match_text":"%1$s"}]}],
              "sensitiveEntities":[{"name":"EMAIL ADDRESS","samples":["%1$s"]}],"fileModifiedTime":1789725004}],
             "total":1}
            """.formatted(RAW);
    private static final String ENDPOINT_OPTIONS = """
            {"success":true,"data":{"categories":["financial"],"subcategories":[],"dataElements":[],
              "tags":[],"uids":["UID-01"],"hostnames":["Team-Laptop-02"],"users":["robin"]}}
            """;
    private static final String ENDPOINT_FILE_OVERVIEW = """
            {"success":true,"overview":{"name":"notes.txt","extension":"txt","owner":"robin",
              "sensitive_entities_count":3,"device":{"hostname":"Team-Laptop-02"},
              "sample_matches":[{"name":"EMAIL ADDRESS","samples":[{"match_text":"%1$s"}]}]}}
            """.formatted(RAW);
    private static final String MIP_AUDIT_LOGS = """
            {"success":true,"audit_logs":[{"action":"TAG_APPLIED","timestamp":1789725004,
              "performed_by":"robin@example.test","selected_tag":{"name":"Confidential"},
              "keywords_written":true,"mip_label_written":true,"sampled_data":["%1$s"]}]}
            """.formatted(RAW);
    private static final String ENDPOINT_LINEAGE_ASSET = """
            {"success":true,"data":{"asset":{"title":"notes.txt","device":"Team-Laptop-02",
              "os_user":"robin","first_seen":1789725004,"sampled_data":["%1$s"]},
              "events":[{"type":"FILE_COPIED","sample_matches":[{"samples":[{"match_text":"%1$s"}]}]}],
              "edges":[],"alerts":[]}}
            """.formatted(RAW);

    /** Every MattersApiClient method the four services call, answered by name. */
    private static final Map<String, Function<InvocationOnMock, String>> BODIES = new HashMap<>();

    static {
        BODIES.put("listAssets", inv -> LIST_ASSETS);
        BODIES.put("listFilters", inv -> LIST_FILTERS);
        BODIES.put("summary", inv -> SUMMARY);
        BODIES.put("inventoryOverview", inv -> INVENTORY_OVERVIEW);
        BODIES.put("inventorySensitives", inv -> INVENTORY_SENSITIVES);
        BODIES.put("inventorySensitiveEntity", inv -> {
            String entity = inv.getArgument(2);
            return INVENTORY_ENTITY.formatted(RAW, entity, "ACCESS KEY AWS".equals(entity) ? "SECRETS" : "PII");
        });
        BODIES.put("inventoryIssues", inv -> INVENTORY_ISSUES);
        BODIES.put("inventoryAccessGraph", inv -> ACCESS_GRAPH);
        BODIES.put("scanStatus", inv -> SCAN_STATUS);
        BODIES.put("scanHistory", inv -> SCAN_HISTORY);
        for (String name : List.of("schemaDatabases", "schemaSchemas", "schemaTables", "schemaDatabaseTables",
                "schemaColumns")) {
            BODIES.put(name, inv -> SCHEMA_PAGE);
        }
        BODIES.put("schemaErd", inv -> ERD);
        BODIES.put("databaseErd", inv -> ERD);
        BODIES.put("tableRelations", inv -> RELATIONS);
        BODIES.put("agentOverview", inv -> AGENT_OVERVIEW);
        BODIES.put("agentFilterOptions", inv -> AGENT_FILTER_OPTIONS);
        BODIES.put("agentSensitiveFiles", inv -> AGENT_SENSITIVE_FILES);
        BODIES.put("agentEntity", inv -> AGENT_ENTITY);
        BODIES.put("agentFiles", inv -> AGENT_FILES);
        BODIES.put("agentScans", inv -> AGENT_SCANS);
        BODIES.put("saasAssetDetail", inv -> SAAS_DETAIL);
        BODIES.put("saasRecords", inv -> "document_classes".equals(inv.getArgument(2))
                ? SAAS_CLASS_RECORDS : SAAS_ELEMENT_RECORDS);
        BODIES.put("saasFiles", inv -> SAAS_FILES);
        BODIES.put("saasScanSummary", inv -> SAAS_SCAN_SUMMARY);
        BODIES.put("saasFileOverview", inv -> SAAS_FILE_OVERVIEW);
        BODIES.put("remediationLogs", inv -> REMEDIATION_LOGS);
        BODIES.put("hashLineageCopies", inv -> COPIES);
        BODIES.put("hashLineageTimeline", inv -> TIMELINE);
        BODIES.put("saasCategories", inv -> SAAS_CATEGORIES);
        BODIES.put("saasSensitivityUsers", inv -> SAAS_USERS);
        BODIES.put("saasSensitivities", inv -> SAAS_SENSITIVITIES);
        BODIES.put("endpointFiles", inv -> ENDPOINT_FILES);
        BODIES.put("endpointAgentsFilterOptions", inv -> ENDPOINT_OPTIONS);
        BODIES.put("endpointFileOverview", inv -> ENDPOINT_FILE_OVERVIEW);
        BODIES.put("endpointLineageAsset", inv -> ENDPOINT_LINEAGE_ASSET);
        BODIES.put("mipFileAuditLogs", inv -> MIP_AUDIT_LOGS);
    }

    /** One request: a path and its query parameters as name/value pairs. */
    private record Call(String path, String... params) {
        MockHttpServletRequestBuilder request() {
            MockHttpServletRequestBuilder builder = get(path);
            for (int i = 0; i < params.length; i += 2) {
                builder.param(params[i], params[i + 1]);
            }
            return builder;
        }

        @Override
        public String toString() {
            return path + " " + Arrays.toString(params);
        }
    }

    private static final Call CLOUD_OCCURRENCES = new Call(DATASTORE + "/sensitives/occurrences",
            "entityName", "PERSON NAME", "skip", "0", "limit", "10", "showFalsePositives", "true");
    private static final Call SECRET_OCCURRENCES = new Call(DATASTORE + "/sensitives/occurrences",
            "entityName", "ACCESS KEY AWS");
    private static final Call ENDPOINT_OCCURRENCES = new Call(ENDPOINT + "/occurrences",
            "entityName", "EMAIL ADDRESS", "skip", "10", "limit", "20", "showFalsePositives", "true");

    /** The union of everyEndpoint() of the four controller tests, with their parameters. */
    private static List<Call> everyEndpoint() {
        return List.of(
                new Call("/api/dspm/datastores", "tab", "cloud", "provider", "S3,EBS"),
                new Call("/api/dspm/datastores/filter-options", "tab", "cloud"),
                new Call("/api/dspm/datastores/kpis", "tab", "cloud"),
                new Call(DATASTORE + "/overview"),
                new Call(DATASTORE + "/scan-status"),
                new Call(DATASTORE + "/sensitives"),
                new Call(DATASTORE + "/sensitives/files", "page", "1", "pageSize", "10", "q", "reports"),
                CLOUD_OCCURRENCES,
                SECRET_OCCURRENCES,
                new Call(DATASTORE + "/risks"),
                new Call(DATASTORE + "/access-graph"),
                new Call(DATASTORE + "/scan-history"),
                new Call(DATASTORE + "/data-flow/databases"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/schemas"),
                new Call("/api/dspm/data-flow/schemas/" + UUID + "/tables", "page", "1", "pageSize", "10"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/tables"),
                new Call("/api/dspm/data-flow/tables/" + UUID + "/columns", "page", "1", "pageSize", "25"),
                new Call("/api/dspm/data-flow/schemas/" + UUID + "/erd"),
                new Call("/api/dspm/data-flow/databases/" + UUID + "/erd"),
                new Call("/api/dspm/data-flow/tables/" + UUID + "/relations"),
                new Call(ENDPOINT + "/overview"),
                new Call(ENDPOINT + "/filter-options"),
                new Call(ENDPOINT + "/summary"),
                new Call(ENDPOINT + "/sensitives", "sensitives", "PII", "documentClass",
                        "system documentation", "showFalsePositives", "true"),
                ENDPOINT_OCCURRENCES,
                new Call(ENDPOINT + "/files", "category", "it & technical", "dataElement", "EMAIL ADDRESS",
                        "q", "notes", "skip", "10", "limit", "25"),
                new Call(ENDPOINT + "/scans", "skip", "10", "limit", "5"),
                new Call(SAAS + "/detail"),
                new Call(SAAS + "/records", "view", "data_elements"),
                new Call(SAAS + "/records", "view", "document_classes"),
                new Call(SAAS + "/files", "dataElement", "EMAIL ADDRESS", "q", "plan", "skip", "0", "limit", "20"),
                new Call(SAAS + "/files", "documentClass", "Budget Plans"),
                new Call(SAAS + "/scan-summary"),
                new Call(SAAS_FILE + "/overview"),
                new Call(SAAS_FILE + "/activity"),
                new Call(SAAS_FILE + "/lineage"),
                new Call(SAAS_FILE + "/copies"),
                new Call(FILES_SAAS, "limit", "25", "q", "plan", "sensitives", "PII", "documentClass",
                        "Travel Receipts", "sharing", "external", "category", "financial", "appType", "GMAIL",
                        "owner", OWNER, "internalUser", "sam@example.test", "externalUser", "lee@example.org",
                        "cursor", "abc+/=="),
                new Call(FILES_SAAS + "/filter-options", "category", "financial"),
                new Call(FILES_ENDPOINT, "skip", "10", "limit", "25", "q", "notes", "sensitives", "PII",
                        "documentClass", "Travel Receipts", "category", "financial", "dataElement", "BADGE NUMBER",
                        "tag", "Reviewed", "uid", "UID-01", "hostname", "Team-Laptop-02", "lastModified", "7d"),
                new Call(FILES_ENDPOINT + "/filter-options"),
                new Call(ENDPOINT_FILE + "/overview"),
                new Call(ENDPOINT_FILE + "/activity"),
                new Call(ENDPOINT_FILE + "/lineage"),
                new Call(ENDPOINT_FILE + "/copies"));
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> unanswered = new ArrayList<>();
    private InMemoryRedisCache cache;
    private MockMvc mvc;
    private Logger root;
    private Level rootLevel;
    private ListAppender<ILoggingEvent> logs;

    private static ClientUserDetails principal(long userId, String role) {
        return new ClientUserDetails("user" + userId + "@example.com", "pw",
                List.of(new SimpleGrantedAuthority(role)), 10L, userId, "tenant-a");
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevel = root.getLevel();
        root.setLevel(Level.ALL);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);

        MattersCredentialResolver resolver = mock(MattersCredentialResolver.class);
        when(resolver.resolve(10L)).thenReturn(CREDENTIAL);
        Answer<Object> planted = inv -> {
            if (inv.getMethod().getReturnType() != JsonNode.class) {
                return RETURNS_DEFAULTS.answer(inv);
            }
            Function<InvocationOnMock, String> body = BODIES.get(inv.getMethod().getName());
            if (body == null) {
                unanswered.add(inv.getMethod().getName());
                throw new IllegalStateException("no planted body for " + inv.getMethod().getName());
            }
            return parse(body.apply(inv));
        };
        MattersApiClient api = mock(MattersApiClient.class, withSettings().defaultAnswer(planted));
        cache = new InMemoryRedisCache();
        DatastoreAccountLabeler labeler = mock(DatastoreAccountLabeler.class);
        when(labeler.label(any(MattersCredential.class), anyList())).thenAnswer(inv -> {
            List<DatastoreRowMapper.MappedRow> rows = inv.getArgument(1);
            return rows.stream().map(DatastoreRowMapper.MappedRow::row).toList();
        });
        when(labeler.label(any(MattersCredential.class), any(DatastoreFilterOptions.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        DatastoreListService listService = new DatastoreListService(resolver, api, new DatastoreRowMapper(), labeler,
                cache);
        DatastoreDrawerService drawerService = new DatastoreDrawerService(resolver, api, new DatastoreDrawerMapper(),
                new AccessGraphGrouper(), cache);
        DatastoreDataFlowService dataFlowService = new DatastoreDataFlowService(resolver, api, new DataFlowMapper(),
                cache);
        EndpointDrawerService endpointDrawerService = new EndpointDrawerService(resolver, api,
                new EndpointDrawerMapper(), cache);
        SaasDrawerService saasDrawerService = new SaasDrawerService(resolver, api, new SaasDrawerMapper(), cache);
        SaasFileService saasFileService = new SaasFileService(resolver, api, new SaasDrawerMapper(), cache);
        FilesService filesService = new FilesService(resolver, api, new FilesPageMapper(), new EndpointDrawerMapper(),
                cache);
        EndpointFileService endpointFileService = new EndpointFileService(resolver, api, new FilesPageMapper(),
                saasFileService, cache);

        GlobalTabRuleReader rules = mock(GlobalTabRuleReader.class);
        UserTabAccessRepository tabs = mock(UserTabAccessRepository.class);
        when(rules.rulesFor(anyLong())).thenReturn(GlobalTabRuleReader.UserRules.NONE);
        when(tabs.findTabNamesByUserId(anyLong())).thenReturn(List.of(TAB));
        XamLensTabGuard guard = new XamLensTabGuard(rules, tabs);
        mvc = MockMvcBuilders.standaloneSetup(
                        new DspmDatastoreController(guard, listService, drawerService, dataFlowService),
                        new DspmEndpointController(guard, endpointDrawerService),
                        new DspmSaasController(guard, saasDrawerService, saasFileService),
                        new DspmFilesController(guard, filesService, endpointFileService))
                .setControllerAdvice(new GlobalExceptionHandler(), new MattersExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        root.detachAppender(logs);
        root.setLevel(rootLevel);
    }

    private void authenticateAs(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Map<Call, String> callEverything() throws Exception {
        Map<Call, String> bodies = new LinkedHashMap<>();
        for (Call call : everyEndpoint()) {
            MvcResult result = mvc.perform(call.request()).andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(result.getResponse().getStatus()).as("%s answered %s", call, body).isEqualTo(200);
            bodies.put(call, body);
        }
        return bodies;
    }

    private void assertNoRawAnywhere(Map<Call, String> bodies) {
        assertThat(unanswered).as("JsonNode methods without a planted body").isEmpty();
        bodies.forEach((call, body) -> assertThat(body).as("response of %s", call)
                .doesNotContain(RAW).doesNotContain(RAW_CORE));
        assertThat(cache.puts).isNotEmpty().allSatisfy(put -> {
            assertThat(put.key()).doesNotContain(RAW_CORE);
            assertThat(put.json()).as("cached value of %s", put.key()).doesNotContain(RAW_CORE);
        });
        assertThat(cache.gets).allSatisfy(key -> assertThat(key).doesNotContain(RAW_CORE));
        assertThat(logs.list).as("the log capture is live").isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(RAW_CORE);
            assertThat(String.valueOf(event.getMessage())).doesNotContain(RAW_CORE);
            assertThat(Arrays.deepToString(event.getArgumentArray())).doesNotContain(RAW_CORE);
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(RAW_CORE);
            }
        });
    }

    private void assertMaskedOccurrences(Map<Call, String> bodies) {
        JsonNode cloud = parse(bodies.get(CLOUD_OCCURRENCES));
        assertThat(cloud.path("rows")).hasSize(2);
        cloud.path("rows").forEach(row -> assertThat(row.path("samples").get(0).asText()).isEqualTo(HALF));
        JsonNode secret = parse(bodies.get(SECRET_OCCURRENCES));
        assertThat(secret.path("rows")).hasSize(2);
        secret.path("rows").forEach(row -> assertThat(row.path("samples").get(0).asText()).isEqualTo(SECRET));
        JsonNode endpoint = parse(bodies.get(ENDPOINT_OCCURRENCES));
        assertThat(endpoint.path("rows").get(0).path("samples").get(0).asText()).isEqualTo(HALF);
    }

    @Test
    @DisplayName("ROLE_XAMOPS: no response, cache key, cached value or log event carries the raw sample")
    void xamopsNeverSeesRaw() throws Exception {
        authenticateAs(principal(500L, "ROLE_XAMOPS"));
        Map<Call, String> bodies = callEverything();
        assertNoRawAnywhere(bodies);
        assertMaskedOccurrences(bodies);
    }

    @Test
    @DisplayName("ROLE_SUPER_ADMIN: the same masked answers; no role sees the raw sample")
    void superAdminNeverSeesRaw() throws Exception {
        authenticateAs(principal(700L, "ROLE_SUPER_ADMIN"));
        Map<Call, String> bodies = callEverything();
        assertNoRawAnywhere(bodies);
        assertMaskedOccurrences(bodies);
    }
}
