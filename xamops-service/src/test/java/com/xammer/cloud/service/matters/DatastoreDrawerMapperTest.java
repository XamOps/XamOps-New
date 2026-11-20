package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOverview;
import com.xammer.cloud.dto.matters.datastores.DatastoreRisks;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanHistory;
import com.xammer.cloud.dto.matters.datastores.DatastoreSensitives;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.6.4 to 5.6.7 and 5.6.9, on synthetic bodies. MARKER strings must never leave the mapper. */
class DatastoreDrawerMapperTest {

    private static final String MARKER = "SYNTHMARKER";

    private final ObjectMapper json = new ObjectMapper();
    private final DatastoreDrawerMapper mapper = new DatastoreDrawerMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
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

    private DatastoreOverview overview(String detailsJson) {
        return mapper.toOverview(parse("{\"success\":true,\"inventories_details\":" + detailsJson + "}"));
    }

    // ── overview whitelist ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only url leaves asset_details; owner, cost, account_name, activities, glossary and metrics are dropped")
    void overviewWhitelist() {
        DatastoreOverview o = overview("""
                {
                  "asset_details": {"url": "https://console.aws.amazon.com/s3/buckets/demo-bucket",
                                    "config": {"connection": "SYNTHMARKER-config"},
                                    "sample_rows": ["SYNTHMARKER-sample"], "endpoint": "SYNTHMARKER-endpoint"},
                  "properties": {"owner": "SYNTHMARKER-owner", "cost": 31337.271828, "account_name": "SYNTHMARKER-acct",
                                 "region": "eu-north-1", "datastore_size": 1024, "arn": "arn:aws:s3:::demo-bucket"},
                  "activities": {"last_scan": "SYNTHMARKER-activity", "last_accessed": "SYNTHMARKER-accessed"},
                  "glossary_terms": ["SYNTHMARKER-glossary"],
                  "security_events_configurations": {"x": "SYNTHMARKER-events"},
                  "total_scanned_files": 918273645, "total_scan_failed": 918273646,
                  "total_files_discovered_for_scanning": 918273647, "total_unsupported_files": 918273648,
                  "scan_metrics_enabled": "SYNTHMARKER-metrics",
                  "canCollectMisconfigurations": true, "isSchemaDiscoveryRunning": "SYNTHMARKER-running",
                  "scanning_status": "SCAN_STOPPED",
                  "sensitive_records": {"sensitive_records_count": 12,
                     "label_wise_sensitive_records_count": {"PII": [{"entity_name": "PERSON NAME", "count": 12,
                        "sampled_data": [{"value": "SYNTHMARKER-entity-sample"}]}]}}
                }
                """);
        String serialised = serialise(o);
        assertThat(serialised).doesNotContain(MARKER).doesNotContain("31337").doesNotContain("91827364")
                .doesNotContain("asset_details").doesNotContain("owner").doesNotContain("account_name");
        assertThat(o.consoleUrl()).isEqualTo("https://console.aws.amazon.com/s3/buckets/demo-bucket");
        assertThat(o.scanningStatusLabel()).isEqualTo("Scan Stopped");
        assertThat(o.canDiscoverSchema()).isFalse();
        assertThat(o.fileSummary()).isNull();
        assertThat(o.securityConfigurations()).isNull();
        assertThat(o.sensitiveRecords().total()).isEqualTo(12);
        assertThat(o.sensitiveRecords().groups()).singleElement().satisfies(g -> {
            assertThat(g.label()).isEqualTo("Personal");
            assertThat(g.entities()).containsExactly(new DatastoreOverview.EntityCount("PERSON NAME", 12));
        });
    }

    @Test
    @DisplayName("consoleUrl only for absolute https with a host, no user-info, at most 2,048 characters")
    void consoleUrlRules() {
        assertThat(DatastoreDrawerMapper.consoleUrl("https://portal.azure.com/#view/x")).isNotNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("HTTPS://console.aws.amazon.com/x")).isNotNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("http://console.aws.amazon.com/x")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("https://bad host/x")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("https://user:pw@console.aws.amazon.com/x")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("javascript:alert(1)")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("/relative/path")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl("https:///no-host")).isNull();
        assertThat(DatastoreDrawerMapper.consoleUrl(null)).isNull();
        String prefix = "https://console.aws.amazon.com/";
        assertThat(DatastoreDrawerMapper.consoleUrl(prefix + "a".repeat(2048 - prefix.length()))).isNotNull();
        assertThat(DatastoreDrawerMapper.consoleUrl(prefix + "a".repeat(2049 - prefix.length()))).isNull();
        assertThat(overview("{\"asset_details\": {\"url\": \"http://x.test/\"}}").consoleUrl()).isNull();
    }

    @Test
    void canDiscoverSchemaFlag() {
        assertThat(overview("{\"canDiscoverSchema\": true}").canDiscoverSchema()).isTrue();
        assertThat(overview("{\"canDiscoverSchema\": \"true\"}").canDiscoverSchema()).isFalse();
        assertThat(overview("{}").canDiscoverSchema()).isFalse();
        assertThat(overview("{}").properties()).isEmpty();
        assertThat(overview("{}").sensitiveRecords().total()).isNull();
        assertThat(overview("{}").sensitiveRecords().groups()).isEmpty();
        assertThat(overview("{\"scanning_status\": \"SCANNING_OUTPUT_PROCESSED\"}").scanningStatusLabel())
                .isEqualTo("Scanning Output Processed");
    }

    // ── file types ───────────────────────────────────────────────────────────────────────────────

    private static List<String> labels(DatastoreOverview o) {
        return o.fileSummary().types().stream().map(DatastoreOverview.FileType::label).toList();
    }

    private static final String ALL_ZERO = """
            {"unknown_files": 0, "xlsx_files": 0, "image_files": 0, "pdf_files": 0, "documents": 0,
             "web_markup": 0, "compressed_files": 0, "autocad": 0, "code_files": 0, "contact_files": 0,
             "databases_files": 0, "email": 0, "log_files": 0, "mailboxes": 0, "parquet_files": 0,
             "presentations": 0, "system_generated_files": 0}
            """;

    @Test
    @DisplayName("the 17-key all-zero shape is alphabetical: Autocad first, XLSX last, Parquet before PDF")
    void allZeroFileTypes() {
        DatastoreOverview o = overview("{\"total_files\": 0, \"file_types\": " + ALL_ZERO + "}");
        assertThat(labels(o)).containsExactly("Autocad", "Code", "Compressed", "Contact", "Databases", "Documents",
                "Email", "Image", "Log", "Mailboxes", "Other", "Parquet", "PDF", "Presentations", "System Generated",
                "Web Markup", "XLSX");
        assertThat(o.fileSummary().totalFiles()).isZero();
    }

    @Test
    @DisplayName("the S3-like mixed shape: non-zero by count, then the zeros alphabetically")
    void mixedFileTypes() {
        DatastoreOverview o = overview("""
                {"total_files": 4000, "file_types": {"unknown_files": 2100, "xlsx_files": 900, "image_files": 500,
                 "pdf_files": 250, "documents": 120, "web_markup": 60, "compressed_files": 7, "autocad": 0,
                 "code_files": 0, "contact_files": 0, "databases_files": 0, "email": 0, "log_files": 0,
                 "mailboxes": 0, "parquet_files": 0, "presentations": 0, "system_generated_files": 0}}
                """);
        assertThat(labels(o)).containsExactly("Other", "XLSX", "Image", "PDF", "Documents", "Web Markup", "Compressed",
                "Autocad", "Code", "Contact", "Databases", "Email", "Log", "Mailboxes", "Parquet", "Presentations",
                "System Generated");
        assertThat(o.fileSummary().types().get(0)).isEqualTo(new DatastoreOverview.FileType("unknown_files", "Other", 2100));
        assertThat(o.fileSummary().totalFiles()).isEqualTo(4000);
    }

    @Test
    @DisplayName("equal non-zero counts tie by label case-insensitively; an unknown key is title-cased")
    void nonZeroTies() {
        DatastoreOverview o = overview("""
                {"file_types": {"pdf_files": 5, "parquet_files": 5, "code_files": 9, "video_files": 5, "autocad": 0}}
                """);
        assertThat(labels(o)).containsExactly("Code", "Parquet", "PDF", "Video", "Autocad");
        assertThat(o.fileSummary().totalFiles()).isNull();
    }

    // ── properties ───────────────────────────────────────────────────────────────────────────────

    private static List<String> propertyLabels(DatastoreOverview o) {
        return o.properties().stream().map(DatastoreOverview.Property::label).toList();
    }

    @Test
    @DisplayName("Azure shape: Region and Datastore Size first, then API order; size 0 is bytes 0")
    void azureProperties() {
        DatastoreOverview o = overview("""
                {"properties": {"subscription_id": "00000000-1111-2222-3333-444444444444", "region": "westus9",
                  "datastore_size": 0, "resource_group": "synthetic-rg", "storage_account": "synthaccount"}}
                """);
        assertThat(propertyLabels(o)).containsExactly("Region", "Datastore Size", "Subscription ID", "Resource Group",
                "Storage Account");
        DatastoreOverview.Property size = o.properties().get(1);
        assertThat(size.bytes()).isZero();
        assertThat(size.text()).isNull();
        assertThat(o.properties().get(0).text()).isEqualTo("westus9");
        assertThat(o.properties().get(0).bytes()).isNull();
    }

    @Test
    @DisplayName("S3 shape: Region, Datastore Size, ARN; account_name, cost and owner absent")
    void s3Properties() {
        DatastoreOverview o = overview("""
                {"properties": {"account_name": "acct", "region": "ap-south-9", "cost": 1.5, "datastore_size": 123456789,
                  "owner": "someone", "arn": "arn:aws:s3:::synthetic-bucket"}}
                """);
        assertThat(propertyLabels(o)).containsExactly("Region", "Datastore Size", "ARN");
        assertThat(o.properties().get(1).bytes()).isEqualTo(123456789L);
        assertThat(o.properties()).extracting(DatastoreOverview.Property::key)
                .doesNotContain("account_name", "cost", "owner");
    }

    @Test
    @DisplayName("Postgres shape: Host, Port with port as plain digits")
    void postgresProperties() {
        DatastoreOverview o = overview("{\"properties\": {\"host\": \"db.synthetic.test\", \"port\": 5432}}");
        assertThat(o.properties()).containsExactly(
                new DatastoreOverview.Property("host", "Host", "db.synthetic.test", null),
                new DatastoreOverview.Property("port", "Port", "5432", null));
    }

    @Test
    @DisplayName("unknown scalars keep API order with derived labels; non-scalars, long text and credentials dropped")
    void propertyShapes() {
        DatastoreOverview o = overview("""
                {"properties": {"project_id": "proj-1", "nested": {"a": 1}, "list": [1, 2], "nothing": null,
                  "huge": "%s", "encrypted": true, "public": false, "db_password": "%s-pw", "API_KEY_ID": "%s-k",
                  "kms_key_id": "key-1", "ratio": 0.25, "blank": "   ", "datastore_size": "2048", "region": null}}
                """.formatted("x".repeat(2049), MARKER, MARKER));
        assertThat(propertyLabels(o)).containsExactly("Datastore Size", "Project ID", "Encrypted", "Public",
                "KMS Key ID", "Ratio");
        assertThat(o.properties().get(0).bytes()).isEqualTo(2048L);
        assertThat(o.properties()).extracting(DatastoreOverview.Property::text)
                .containsExactly(null, "proj-1", "Yes", "No", "key-1", "0.25");
        assertThat(serialise(o)).doesNotContain(MARKER);

        DatastoreOverview textSize = overview("{\"properties\": {\"datastore_size\": \"about 2 GB\"}}");
        assertThat(textSize.properties()).containsExactly(
                new DatastoreOverview.Property("datastore_size", "Datastore Size", "about 2 GB", null));
    }

    // ── security configurations ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("security configurations: prettified strings, booleans as Enabled / Disabled, null skipped, extra key dropped")
    void securityConfigurations() {
        DatastoreOverview o = overview("""
                {"security_configurations": {"bucket_versioning": "ENABLED", "default_encryption": "SSE_S3",
                  "public_accessibility": false, "logging": null, "ssl_enforced": true, "mfa_delete": "Enabled"}}
                """);
        assertThat(o.securityConfigurations()).containsExactly(
                new DatastoreOverview.SecurityConfiguration("bucket_versioning", "Bucket Versioning", "Enabled"),
                new DatastoreOverview.SecurityConfiguration("default_encryption", "Default Encryption", "Sse S3"),
                new DatastoreOverview.SecurityConfiguration("public_accessibility", "Public Accessibility", "Disabled"),
                new DatastoreOverview.SecurityConfiguration("ssl_enforced", "SSL Enforced", "Enabled"));
        assertThat(overview("{\"security_configurations\": {\"logging\": \"Enabled\"}}").securityConfigurations())
                .extracting(DatastoreOverview.SecurityConfiguration::value).containsExactly("Enabled");
        assertThat(overview("{\"security_configurations\": null}").securityConfigurations()).isNull();
    }

    // ── sensitives ───────────────────────────────────────────────────────────────────────────────

    private static final String SENSITIVES = """
            {"success": true,
             "entity_level_response": [
               {"entity_name": "PERSON NAME", "count": 40, "labels": ["PII"]},
               {"entity_name": "", "count": 0, "labels": []},
               {"entity_name": "SHA KEY", "count": 70, "labels": ["SECRETS", "PII"]},
               {"entity_name": null, "count": 0, "labels": []}
             ],
             "inventories_sensitives": [{"path": "SYNTHMARKER-inv", "false_positive": {"x": "SYNTHMARKER-fp"}}],
             "database_level_response": {"databases": [{"id": "db-1", "name": "synth_db", "tables": [
               {"name": "t_low", "row_id": "SYNTHMARKER-row", "schema_name": "public", "count": 3, "sensitive_entities": []},
               {"name": "t_tie_a", "schema_name": "public", "count": 10, "sensitive_entities": [
                  {"entity_type": "EMAIL ADDRESS", "column_name": "email", "identified_labels": ["PII"], "count": 10,
                   "identified_by_user": "SYNTHMARKER-user", "isFalsePositive": true, "url": "s3a://SYNTHMARKER/x/"}]},
               {"name": "t_high", "schema_name": "app", "count": 25, "sensitive_entities": [
                  {"entity_type": "SHA KEY", "column_name": "payload", "identified_labels": ["SECRETS", "PII"], "count": 20},
                  {"entity_type": "", "column_name": "note", "identified_labels": ["PII"], "count": 5}]},
               {"name": "t_tie_b", "schema_name": "public", "count": 10, "sensitive_entities": [
                  {"entity_type": null, "column_name": "c", "identified_labels": [], "count": 10}]}
             ]}]},
             "file_level_sensitives_count": {
               "s3a://bucket/id/synth_db/public/t_high/": {
                  "SHA KEY": {"entityCount": 20, "columnName": "SYNTHMARKER-col", "labels": ["SECRETS"],
                              "reason": "SYNTHMARKER-reason", "sampled_data": ["SYNTHMARKER-sample"]}}}
            }
            """;

    @Test
    @DisplayName("entities: the first blank entity moves last, further blanks are dropped")
    void sensitiveEntities() {
        DatastoreSensitives s = mapper.toSensitives(parse(SENSITIVES));
        assertThat(s.entities()).containsExactly(
                new DatastoreSensitives.Entity("PERSON NAME", 40, "Personal", false),
                new DatastoreSensitives.Entity("SHA KEY", 70, "IT & Security", false),
                new DatastoreSensitives.Entity("", 0, null, true));
    }

    @Test
    @DisplayName("tables sorted by count with stable ties; label union sorted; expandable; findings")
    void sensitiveTables() {
        DatastoreSensitives s = mapper.toSensitives(parse(SENSITIVES));
        assertThat(s.database()).isTrue();
        DatastoreSensitives.Database db = s.databases().get(0);
        assertThat(db.id()).isEqualTo("db-1");
        assertThat(db.tables()).extracting(DatastoreSensitives.Table::name)
                .containsExactly("t_high", "t_tie_a", "t_tie_b", "t_low");
        DatastoreSensitives.Table high = db.tables().get(0);
        assertThat(high.labels()).containsExactly("IT & Security", "Personal");
        assertThat(high.schemaName()).isEqualTo("app");
        assertThat(high.expandable()).isTrue();
        assertThat(high.findings()).containsExactly(new DatastoreSensitives.ColumnFinding("payload", "SHA KEY",
                List.of("IT & Security", "Personal"), 20, false));
        assertThat(db.tables().get(1).findings().get(0).falsePositive()).isTrue();
        assertThat(db.tables().get(2).expandable()).isFalse();
        assertThat(db.tables().get(3).expandable()).isFalse();
        assertThat(db.tables().get(3).labels()).isEmpty();
    }

    @Test
    void noDatabaseLevelResponse() {
        DatastoreSensitives s = mapper.toSensitives(parse(
                "{\"entity_level_response\": [], \"database_level_response\": null}"));
        assertThat(s.database()).isFalse();
        assertThat(s.databases()).isEmpty();
        assertThat(s.entities()).isEmpty();
    }

    // ── file rows ────────────────────────────────────────────────────────────────────────────────

    private static final String FILES = """
            {"file_level_sensitives_count": {
              "s3://synth-bucket/z-last-alpha.txt": {
                 "EMAIL ADDRESS": {"entityCount": 4, "columnName": "SYNTHMARKER-col", "labels": ["PII"],
                                   "reason": "SYNTHMARKER-reason", "sampled_data": [{"value": "SYNTHMARKER-s1"}]},
                 "ACCESS KEY AWS": {"entityCount": 1, "labels": ["SECRETS"], "sampled_data": ["SYNTHMARKER-s2"]}},
              "s3://synth-bucket/archive/2030/report.CSV": {
                 "": {"entityCount": 0, "labels": ["PII"], "sampled_data": []}},
              "s3a://bucket/id/db/public/agent_events/": {
                 "IP ADDRESS": {"entityCount": 7, "labels": ["PII"]}}
            }}
            """;

    @Test
    @DisplayName("file rows in API key order, blank and zero rows kept, counts summed, labels sorted, basename")
    void fileRows() {
        List<DatastoreFilePage.FileRow> rows = mapper.toFileRows(parse(FILES));
        assertThat(rows).extracting(DatastoreFilePage.FileRow::name)
                .containsExactly("z-last-alpha.txt", "report.CSV", "agent_events");
        DatastoreFilePage.FileRow first = rows.get(0);
        assertThat(first.count()).isEqualTo(5);
        assertThat(first.labels()).containsExactly("IT & Security", "Personal");
        assertThat(first.entities()).containsExactly(
                new DatastoreFilePage.FileEntity("EMAIL ADDRESS", "Personal"),
                new DatastoreFilePage.FileEntity("ACCESS KEY AWS", "IT & Security"));
        assertThat(rows.get(1).count()).isZero();
        assertThat(rows.get(1).entities()).containsExactly(new DatastoreFilePage.FileEntity("", "Personal"));
        assertThat(rows.get(2).path()).isEqualTo("s3a://bucket/id/db/public/agent_events/");
        assertThat(serialise(rows)).doesNotContain(MARKER);
        assertThat(mapper.toFileRows(parse("{\"file_level_sensitives_count\": null}"))).isEmpty();
    }

    @Test
    @DisplayName("the file page filters by path case-insensitively and slices by page")
    void filePage() {
        List<DatastoreFilePage.FileRow> rows = mapper.toFileRows(parse(FILES));
        DatastoreFilePage searched = mapper.filePage(rows, "ARCHIVE", 1, 10);
        assertThat(searched.rows()).extracting(DatastoreFilePage.FileRow::name).containsExactly("report.CSV");
        assertThat(searched.total()).isEqualTo(1);
        assertThat(searched.truncated()).isFalse();

        DatastoreFilePage second = mapper.filePage(rows, null, 2, 2);
        assertThat(second.rows()).extracting(DatastoreFilePage.FileRow::name).containsExactly("agent_events");
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(mapper.filePage(rows, null, 5, 2).rows()).isEmpty();
        assertThat(mapper.filePage(List.of(), null, 1, 10).totalPages()).isZero();
    }

    @Test
    @DisplayName("rows beyond 50,000 are dropped and the page reports truncated")
    void fileRowCap() {
        ObjectNode files = json.createObjectNode();
        for (int i = 0; i < DatastoreDrawerMapper.MAX_FILE_ROWS + 5; i++) {
            files.putObject("s3://synth/f" + i).putObject("PERSON NAME").put("entityCount", 1);
        }
        ObjectNode body = json.createObjectNode();
        body.set("file_level_sensitives_count", files);
        DatastoreFilePage page = mapper.filePage(mapper.toFileRows(body), null, 1, 10);
        assertThat(page.truncated()).isTrue();
        assertThat(page.total()).isEqualTo(DatastoreDrawerMapper.MAX_FILE_ROWS);

        files.removeAll();
        for (int i = 0; i < DatastoreDrawerMapper.MAX_FILE_ROWS; i++) {
            files.putObject("s3://synth/f" + i);
        }
        DatastoreFilePage exact = mapper.filePage(mapper.toFileRows(body), null, 1, 10);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.total()).isEqualTo(DatastoreDrawerMapper.MAX_FILE_ROWS);
    }

    @Test
    void basename() {
        assertThat(DatastoreDrawerMapper.basename("s3a://bucket/id/db/public/agent_events/")).isEqualTo("agent_events");
        assertThat(DatastoreDrawerMapper.basename("s3://b/dir/file.csv")).isEqualTo("file.csv");
        assertThat(DatastoreDrawerMapper.basename("plain")).isEqualTo("plain");
        assertThat(DatastoreDrawerMapper.basename("///")).isEqualTo("///");
        assertThat(DatastoreDrawerMapper.basename(null)).isNull();
    }

    @Test
    @DisplayName("no sample marker in the serialised overview, sensitives or file rows")
    void noSampleMarkers() {
        assertThat(serialise(mapper.toSensitives(parse(SENSITIVES)))).doesNotContain(MARKER);
        assertThat(serialise(mapper.toFileRows(parse(SENSITIVES)))).doesNotContain(MARKER);
        assertThat(serialise(mapper.toFileRows(parse(FILES)))).doesNotContain(MARKER);
    }

    // ── occurrences ──────────────────────────────────────────────────────────────────────────────

    private static final String OCCURRENCES = """
            {"success": true, "entity_level_response": {
               "entity_name": "PERSON NAME", "labels": ["PII"], "total_sensitive_occurrences": 738,
               "pagination": {"total": 72, "skip": 0, "limit": 80},
               "data": [
                 {"url": "s3://synth/dir/one.csv", "column_name": "name", "is_false_positive": false,
                  "database_name": null, "schema_name": null, "table_name": null, "topic_name": "t", "page_number": 3,
                  "sampled_data": [{"value": "SYNTHMARKER-a"}, {"value": null}, {"value": "SYNTHMARKER-b"}]},
                 {"url": "", "database_name": "db", "schema_name": "public", "table_name": "users",
                  "column_name": "email", "is_false_positive": true}
               ]}}
            """;

    @Test
    @DisplayName("occurrences: samples in order, skip and limit echo the request, total from pagination.total")
    void occurrences() {
        DatastoreOccurrencePage page = mapper.toOccurrences(parse(OCCURRENCES), "PERSON NAME", 0, 10);
        assertThat(page.total()).isEqualTo(72);
        assertThat(page.skip()).isZero();
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.entityName()).isEqualTo("PERSON NAME");
        DatastoreOccurrencePage.Occurrence first = page.rows().get(0);
        assertThat(first.samples()).containsExactly("SYNTH••••••-•", "SYNTH••••••-•");
        assertThat(first.fileName()).isEqualTo("one.csv");
        assertThat(first.url()).isEqualTo("s3://synth/dir/one.csv");
        DatastoreOccurrencePage.Occurrence second = page.rows().get(1);
        assertThat(second.samples()).isNotNull().isEmpty();
        assertThat(second.fileName()).isNull();
        assertThat(second.url()).isNull();
        assertThat(second.tableName()).isEqualTo("users");
        assertThat(second.falsePositive()).isTrue();
        assertThat(serialise(page)).doesNotContain("topic_name").doesNotContain("page_number");
    }

    @Test
    @DisplayName("occurrences: a SECRETS-labelled entity's samples use the secret rule (first 4 visible)")
    void occurrencesSecretRule() {
        DatastoreOccurrencePage page = mapper.toOccurrences(parse("""
                {"entity_level_response": {"entity_name": "ACCESS KEY AWS", "labels": ["SECRETS"],
                   "data": [{"url": "s3://synth/k.env", "sampled_data": [{"value": "AKIAIOSFODNN7EXAMPLE"}]}]}}
                """), "ACCESS KEY AWS", 0, 10);
        assertThat(page.rows().get(0).samples()).containsExactly("AKIA••••••••••••••••");
        assertThat(serialise(page)).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }
    @Test
    @DisplayName("an Occurrence built without the mapper still masks its samples (the DTO floor)")
    void occurrenceFloor() {
        DatastoreOccurrencePage.Occurrence row = new DatastoreOccurrencePage.Occurrence(
                "a.csv", "s3://a.csv", null, null, null, null, false, List.of("Jane Doe"));
        assertThat(row.samples()).containsExactly("Ja•• D••");
        assertThat(new DatastoreOccurrencePage.Occurrence(null, null, null, null, null, null, false, null).samples())
                .isNotNull().isEmpty();
    }
    @Test
    @DisplayName("without a numeric pagination.total, skip 20 with 4 rows gives 24")
    void occurrenceTotalFallback() {
        DatastoreOccurrencePage page = mapper.toOccurrences(parse("""
                {"entity_level_response": {"pagination": {"total": "n/a"}, "data": [{}, {}, {}, {}]}}
                """), "EMAIL ADDRESS", 20, 10);
        assertThat(page.total()).isEqualTo(24);
        assertThat(page.skip()).isEqualTo(20);
    }

    @Test
    @DisplayName("Occurrence.toString() shows how many samples, never a sample")
    void occurrenceToStringRedacts() {
        DatastoreOccurrencePage page = mapper.toOccurrences(parse(OCCURRENCES), "PERSON NAME", 0, 10);
        assertThat(page.toString()).doesNotContain(MARKER).contains("samples=<2 values>").contains("samples=<0 values>");
        assertThat(page.rows().get(0).toString()).doesNotContain(MARKER).contains("samples=<2 values>");
    }

    // ── risks, scan history, progress ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("risks: severity upper-cased with its label, dropped fields absent, blank cli null")
    void risks() {
        DatastoreRisks risks = mapper.toRisks(parse("""
                {"risk": [{"risk": "Bucket logging disabled", "source": "upwind", "severity": "medium",
                  "description": "Desc", "remediation": "Fix", "remediation_recommendation_code_cli": "  ",
                  "policy_id": "SYNTHMARKER-policy", "learn_more": "SYNTHMARKER-learn",
                  "compliance": [{"name": "SYNTHMARKER-comp"}], "details": {"x": "SYNTHMARKER-details"}},
                 {"risk": "Second", "source": "MATTERS", "severity": "CRITICAL",
                  "remediation_recommendation_code_cli": "aws s3api put-bucket-logging"}]}
                """));
        assertThat(risks.items().get(0)).isEqualTo(new DatastoreRisks.Misconfiguration("Bucket logging disabled",
                "UPWIND", "MEDIUM", "Medium", "Desc", "Fix", null));
        assertThat(risks.items().get(1).severityLabel()).isEqualTo("Critical");
        assertThat(risks.items().get(1).cli()).isEqualTo("aws s3api put-bucket-logging");
        assertThat(serialise(risks)).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("scan history labels; id and end_time dropped")
    void scanHistory() {
        DatastoreScanHistory history = mapper.toScanHistory(parse("""
                {"scan_history": [
                  {"id": "SYNTHMARKER-id", "status": "SCAN_STOPPED", "start_time": 1700001000, "end_time": 1700009999,
                   "execution_number": 2, "triggered_by": "someone@example.com", "count_of_data_at_risk": 15,
                   "sensitivity_level": "CRITICAL_SENSITIVITY"},
                  {"status": "COMPLETED", "execution_number": 1, "sensitivity_level": "NOT_SENSITIVE"}]}
                """));
        assertThat(history.runs().get(0)).isEqualTo(new DatastoreScanHistory.Run(2, 1700001000L, 15L,
                "CRITICAL_SENSITIVITY", "Critical", "someone@example.com", "SCAN_STOPPED", "Stopped"));
        DatastoreScanHistory.Run second = history.runs().get(1);
        assertThat(second.severityLabel()).isNull();
        assertThat(second.statusLabel()).isEqualTo("Completed");
        assertThat(second.startTime()).isNull();
        assertThat(serialise(history)).doesNotContain(MARKER).doesNotContain("1700009999");
    }

    @Test
    @DisplayName("scan progress only when numeric")
    void scanProgress() {
        assertThat(mapper.toScanProgress(parse("{\"scan_progress\": 100.0, \"stages\": []}")).progress()).isEqualTo(100.0);
        assertThat(mapper.toScanProgress(parse("{\"scan_progress\": 42}")).progress()).isEqualTo(42.0);
        assertThat(mapper.toScanProgress(parse("{\"scan_progress\": \"100\"}")).progress()).isNull();
        assertThat(mapper.toScanProgress(parse("{}")).progress()).isNull();
    }
}
