package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.EndpointMetric;
import com.xammer.cloud.dto.matters.datastores.EndpointOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.EndpointOverview;
import com.xammer.cloud.dto.matters.datastores.EndpointScanPage;
import com.xammer.cloud.dto.matters.datastores.EndpointSummary;
import com.xammer.cloud.dto.matters.datastores.EndpointSummarySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6.2 and 5.6.3 on synthetic bodies shaped like the vendor's. Dropped fields carry
 * {@code MARKER} values; only the occurrences page may carry a sample (AC19).
 */
class EndpointDrawerMapperTest {

    private static final String MARKER = "MARKER";

    private final ObjectMapper json = new ObjectMapper();
    private final EndpointDrawerMapper mapper = new EndpointDrawerMapper();

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

    /** A sensitive-files answer: 34 extension keys in scrambled order, two label groups, six metrics. */
    private static final String SENSITIVE_FILES = """
            {"success": true, "data": {
              "total_files": 812,
              "extension_counts": {"yml": 39, "bak3": 1, "java": 120, "shell": 1, "tsx": 95, "crt": 1, "md": 39,
                "ts": 95, "csv": 1, "sql": 51, "dev": 1, "css": 44, "docx": 1, "yaml": 30, "jmx": 1, "sh": 22,
                "key": 1, "go": 20, "mp3": 1, "py": 18, "ps1": 1, "json": 15, "tsbuildinfo": 1, "js": 12,
                "html": 12, "xml": 9, "txt": 8, "conf": 6, "mjs": 5, "properties": 4, "sum": 3, "cmd": 2,
                "env": 2, "pdf": 2, "weird": "MARKER-COUNT"},
              "label_wise_sensitive_entities": {
                "PII": [{"entity_name": "EMAIL ADDRESS", "count": 4}, {"entity_name": "PERSON NAME", "count": 2}],
                "SECRETS": [{"entity_name": "API KEY", "count": 1}]},
              "category_subcategory_counts": {
                "it & technical": {"technical manuals": 5, "incident reports": 2, "system documentation": 11,
                                   "software licenses": 4},
                "identity & personal": {"resumes": 2}},
              "total_sensitives_count": 6,
              "sampleMatches": [{"name": "EMAIL ADDRESS", "samples": [{"match_text": "MARKER-SAMPLE"}]}],
              "sampled_data": ["MARKER-SAMPLE"],
              "unified_metrics": [
                {"label": "PII", "entity_name": "EMAIL ADDRESS", "category": "", "subcategory": "", "count": 4},
                {"label": "PII", "entity_name": "PERSON NAME", "category": "", "subcategory": "", "count": 2},
                {"label": "", "entity_name": "", "category": "it & technical", "subcategory": "technical manuals",
                 "count": 5},
                {"label": "", "entity_name": "", "category": "it & technical", "subcategory": "incident reports",
                 "count": 2},
                {"label": "", "entity_name": "", "category": "it & technical", "subcategory": "system documentation",
                 "count": 11},
                {"label": "", "entity_name": "", "category": "it & technical", "subcategory": "software licenses",
                 "count": 4},
                {"label": "PII", "entity_name": " ", "category": "", "subcategory": " ", "count": 50}]}}
            """;

    @Test
    @DisplayName("overview: INACTIVE is Offline, decimal epochs floored, fallbacks, and no os or other dropped field")
    void overview() {
        EndpointOverview overview = mapper.toOverview(parse("""
                {"success": true, "data": {"id": "MARKER-ID", "user": "MARKER-USER", "device_serial_number": null,
                  "device_number": "SER-0001", "os": "MARKER-OS", "count_of_data_at_risk": 99,
                  "labels": ["MARKER-LABEL"], "sensitivity": "high", "agent_status": "INACTIVE",
                  "agent_version": "1.2.3", "users": ["Robin", " ", 5, "Sam"], "device_type": "laptop",
                  "os_version": "14.2.1", "architecture": "arm64", "public_ip": "198.51.100.7",
                  "local_ip": "MARKER-LOCAL", "mac_address": "00:11:22:33:44:55", "memory": "16 GB",
                  "cpu_usage": 12.5, "memory_usage": 40.25, "hostname": "Robin Laptop", "last_active": null,
                  "email_id": "MARKER-EMAIL", "os_type": "MARKER-OSTYPE", "last_ping_time": 1789725004377,
                  "last_scan_time": 1789725004.377114,
                  "category_subcategory_counts": {"MARKER-CATEGORY": {"MARKER-CLASS": 1}}}}
                """));
        assertThat(overview).isEqualTo(new EndpointOverview("SER-0001", 1789725004L, 1789725004L, "High",
                "Offline", false, List.of("Robin", "Sam"), "Robin Laptop", "198.51.100.7", "00:11:22:33:44:55",
                "Laptop", "14.2.1", "arm64", "16 GB", 12.5, 40.25, "1.2.3"));
        assertThat(serialise(overview)).doesNotContain(MARKER).doesNotContain("\"os\"")
                .doesNotContain("99").doesNotContain("localIp");

        EndpointOverview online = mapper.toOverview(parse("""
                {"data": {"device_serial_number": "SER-0002", "device_number": "SER-OTHER", "agent_status": "active",
                  "last_active": 1789725100, "last_ping_time": 1789725004, "cpu_usage": "12", "sensitivity": null}}
                """));
        assertThat(online.serialNumber()).isEqualTo("SER-0002");
        assertThat(online.agentStatusLabel()).isEqualTo("Online");
        assertThat(online.agentOnline()).isTrue();
        assertThat(online.lastAccessAt()).isEqualTo(1789725100L);
        assertThat(online.cpuUsage()).isNull();
        assertThat(online.severityLabel()).isNull();

        EndpointOverview empty = mapper.toOverview(parse("{}"));
        assertThat(empty.users()).isEmpty();
        assertThat(empty.agentOnline()).isFalse();
        assertThat(empty.agentStatusLabel()).isNull();
    }

    @Test
    @DisplayName("filter options: four dimensions in order, text values only, counts null, hasMore false")
    void filterOptions() {
        DatastoreFilterOptions options = mapper.toFilterOptions(parse("""
                {"success": true, "data": {"categories": ["it & technical", " "],
                  "subcategories": ["system documentation", "incident reports"], "sensitives": ["PII", 7],
                  "dataElements": ["EMAIL ADDRESS", "PERSON NAME"]}}
                """));
        assertThat(options.dimensions()).containsExactly(
                new DatastoreFilterOptions.Dimension("sensitives",
                        List.of(new DatastoreFilterOptions.Option("PII", "Personal", null)), false),
                new DatastoreFilterOptions.Dimension("documentClass", List.of(
                        new DatastoreFilterOptions.Option("system documentation", "system documentation", null),
                        new DatastoreFilterOptions.Option("incident reports", "incident reports", null)), false),
                new DatastoreFilterOptions.Dimension("category",
                        List.of(new DatastoreFilterOptions.Option("it & technical", "IT & Technical", null)), false),
                new DatastoreFilterOptions.Dimension("dataElement", List.of(
                        new DatastoreFilterOptions.Option("EMAIL ADDRESS", "EMAIL ADDRESS", null),
                        new DatastoreFilterOptions.Option("PERSON NAME", "PERSON NAME", null)), false));
        assertThat(mapper.toFilterOptions(parse("{}")).dimensions())
                .extracting(DatastoreFilterOptions.Dimension::key)
                .containsExactly("sensitives", "documentClass", "category", "dataElement");
    }

    @Test
    @DisplayName("summary: 34 extensions by count then key, label groups and file categories in API order")
    void summary() {
        EndpointSummary summary = mapper.toSummary(parse(SENSITIVE_FILES));
        assertThat(summary.totalFiles()).isEqualTo(812L);
        assertThat(summary.totalSensitives()).isEqualTo(6L);
        assertThat(summary.extensions()).hasSize(34);
        assertThat(summary.extensions().stream().map(e -> e.label() + " " + e.count()).collect(Collectors.joining(", ")))
                .isEqualTo("JAVA 120, TS 95, TSX 95, SQL 51, CSS 44, MD 39, YML 39, YAML 30, SH 22, GO 20, PY 18, "
                        + "JSON 15, HTML 12, JS 12, XML 9, TXT 8, CONF 6, MJS 5, Properties 4, Sum 3, Cmd 2, Env 2, "
                        + "PDF 2, Bak3 1, Crt 1, CSV 1, Dev 1, DOCX 1, Jmx 1, Key 1, MP3 1, Ps1 1, Shell 1, "
                        + "Tsbuildinfo 1");
        assertThat(summary.extensions().get(0).extension()).isEqualTo("java");

        assertThat(summary.labelGroups()).containsExactly(
                new EndpointSummary.LabelGroup("Personal", List.of(
                        new EndpointSummary.EntityCount("EMAIL ADDRESS", 4),
                        new EndpointSummary.EntityCount("PERSON NAME", 2))),
                new EndpointSummary.LabelGroup("IT & Security", List.of(
                        new EndpointSummary.EntityCount("API KEY", 1))));
        assertThat(summary.fileCategories()).containsExactly(
                new EndpointSummary.CategoryGroup("IT & Technical", List.of(
                        new EndpointSummary.EntityCount("technical manuals", 5),
                        new EndpointSummary.EntityCount("incident reports", 2),
                        new EndpointSummary.EntityCount("system documentation", 11),
                        new EndpointSummary.EntityCount("software licenses", 4))),
                new EndpointSummary.CategoryGroup("Identity & Personal", List.of(
                        new EndpointSummary.EntityCount("resumes", 2))));

        String serialised = serialise(summary);
        assertThat(serialised).doesNotContain(MARKER).doesNotContain("metrics").doesNotContain("unified")
                .doesNotContain("entityNames").doesNotContain("weird");

        EndpointSummary empty = mapper.toSummary(parse("{}"));
        assertThat(empty.totalFiles()).isNull();
        assertThat(empty.extensions()).isEmpty();
        assertThat(empty.labelGroups()).isEmpty();
        assertThat(empty.fileCategories()).isEmpty();
    }

    @Test
    @DisplayName("summary snapshot: the summary plus the ENTITY names in API order")
    void summarySnapshot() {
        EndpointSummarySnapshot snapshot = mapper.toSummarySnapshot(parse(SENSITIVE_FILES));
        assertThat(snapshot.entityNames()).containsExactly("EMAIL ADDRESS", "PERSON NAME");
        assertThat(snapshot.summary()).isEqualTo(mapper.toSummary(parse(SENSITIVE_FILES)));
        assertThat(serialise(snapshot)).doesNotContain(MARKER);
    }

    @Test
    @DisplayName("metrics: entity rows and document-class rows, count descending with ties in API order")
    void metrics() {
        List<EndpointMetric> metrics = mapper.toMetrics(parse(SENSITIVE_FILES)).metrics();
        assertThat(metrics).containsExactly(
                new EndpointMetric("DOCUMENT_CLASS", "system documentation", "IT & Technical", 11),
                new EndpointMetric("DOCUMENT_CLASS", "technical manuals", "IT & Technical", 5),
                new EndpointMetric("ENTITY", "EMAIL ADDRESS", "Personal", 4),
                new EndpointMetric("DOCUMENT_CLASS", "software licenses", "IT & Technical", 4),
                new EndpointMetric("ENTITY", "PERSON NAME", "Personal", 2),
                new EndpointMetric("DOCUMENT_CLASS", "incident reports", "IT & Technical", 2));
        assertThat(serialise(mapper.toMetrics(parse(SENSITIVE_FILES)))).doesNotContain(MARKER);
        assertThat(mapper.toMetrics(parse("{}")).metrics()).isEmpty();
    }

    @Test
    @DisplayName("occurrences: only the entity's match_text, untrimmed, scalars kept, at most 20; toString hides them")
    void occurrences() {
        String twentyFive = IntStream.range(0, 25).mapToObj(i -> "{\"match_text\": \"s" + i + "\"}")
                .collect(Collectors.joining(","));
        EndpointOccurrencePage page = mapper.toOccurrences(parse("""
                {"success": true, "data": [
                  {"id": "MARKER-ID", "name": "notes.txt", "extension": "txt", "path": "/Users/synthetic/notes.txt",
                   "labels": ["PII"], "sensitiveEntitiesCount": 3,
                   "sampleMatches": [
                     {"name": "EMAIL ADDRESS", "label": "PII", "samples": [
                       {"match_text": "  r***@example.test ", "line_no": null}, {"match_text": 12345},
                       {"match_text": null}, {"match_text": {"x": "MARKER-CONTAINER"}}, {"line_no": 3}]},
                     {"name": "PERSON NAME", "label": "PII", "samples": [{"match_text": "MARKER-OTHER-ENTITY"}]}],
                   "tags": ["reviewed"], "isFalsePositive": false, "dataHash": "MARKER-HASH",
                   "sensitiveEntities": [{"name": "MARKER-ENTITY"}]},
                  {"name": "plan.md", "path": "/Users/synthetic/plan.md", "isFalsePositive": true,
                   "sampleMatches": [{"name": "EMAIL ADDRESS", "samples": [%s]}]}],
                 "total": 42}
                """.formatted(twentyFive)), "EMAIL ADDRESS", 10, 10);

        assertThat(page.entityName()).isEqualTo("EMAIL ADDRESS");
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.skip()).isEqualTo(10);
        assertThat(page.limit()).isEqualTo(10);
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().get(0)).isEqualTo(new EndpointOccurrencePage.Occurrence("notes.txt",
                "/Users/synthetic/notes.txt", List.of("reviewed"), false, List.of("  r***@example.test ", "12345")));
        assertThat(page.rows().get(1).falsePositive()).isTrue();
        assertThat(page.rows().get(1).samples()).hasSize(20).startsWith("s0").endsWith("s19");
        assertThat(page.rows().get(1).tags()).isEmpty();
        assertThat(serialise(page)).doesNotContain(MARKER);

        String logLine = page.rows().get(0).toString();
        assertThat(logLine).contains("fileName=notes.txt").contains("samples=<2 values>")
                .doesNotContain("example.test").doesNotContain("12345");

        EndpointOccurrencePage noTotal = mapper.toOccurrences(parse("{\"data\": [{\"name\": \"a.txt\"}]}"),
                "EMAIL ADDRESS", 20, 10);
        assertThat(noTotal.total()).isEqualTo(21);
        assertThat(noTotal.rows().get(0).samples()).isEmpty();
    }

    @Test
    @DisplayName("file page: title-case category and class, display labels, no sample or dropped field")
    void filePage() {
        EndpointFilePage page = mapper.toFilePage(parse("""
                {"success": true, "data": [
                  {"id": "MARKER-ID", "name": "Setup Guide.PDF", "extension": "PDF", "type": "MARKER-TYPE",
                   "size": 204800, "path": "/Users/synthetic/Docs/Setup Guide.PDF", "scanStatus": "MARKER-SCAN",
                   "labels": ["PII", " "], "sensitiveEntitiesCount": 4,
                   "sampleMatches": [{"name": "EMAIL ADDRESS", "label": "PII",
                                      "samples": [{"match_text": "MARKER-SAMPLE"}]}],
                   "currentUser": "ROBIN", "isDeleted": false, "tags": [], "fileModifiedTime": 1789725004,
                   "fileCreatedTime": 1700000001, "fileAccessedTime": 1700000002, "category": "it & technical",
                   "subcategory": "system documentation",
                   "sensitiveEntities": [{"label": ["PII"], "count": 4, "name": "MARKER-ENTITY"}],
                   "createdAt": 1700000003, "updatedAt": 1700000004, "dataHash": "MARKER-HASH"},
                  {"name": "bare"}],
                 "total": 2}
                """), 0, 10);
        assertThat(page.rows()).containsExactly(
                new EndpointFilePage.File(null, "Setup Guide.PDF", "pdf", "/Users/synthetic/Docs/Setup Guide.PDF",
                        "ROBIN", 204800L, 4, List.of("Personal"), "It & Technical", "System Documentation", List.of(),
                        1789725004L, 1700000004L),
                new EndpointFilePage.File(null, "bare", null, null, null, null, 0, List.of(), null, null, List.of(),
                        null, null));
        assertThat(page.total()).isEqualTo(2);
        assertThat(serialise(page)).doesNotContain(MARKER).doesNotContain("1700000001")
                .doesNotContain("1700000003");
        assertThat(mapper.toFilePage(parse("{}"), 30, 10).total()).isEqualTo(30);
    }

    @Test
    @DisplayName("scans: API order, status labels, a negative total_files and a null end_time stay null")
    void scans() {
        EndpointScanPage page = mapper.toScanPage(parse("""
                {"success": true, "data": [
                  {"id": "MARKER-ID", "account_id": "MARKER-ACCOUNT", "status": "completed", "scan_type": "MARKER-TYPE",
                   "start_time": 1789725004.5, "end_time": 1789725300, "processed_files": 10, "sensitive_files": 3,
                   "total_files": 10, "agent_id": "MARKER-AGENT", "extension_counts": {"MARKER": 1}},
                  {"status": "IN_PROGRESS", "start_time": 1789726000, "end_time": null, "sensitive_files": 0,
                   "total_files": -1},
                  {"status": "TIMED_OUT", "start_time": "2026-09-10T09:15:00Z", "total_files": "12"}],
                 "count": 7}
                """), 0, 10);
        assertThat(page.rows()).containsExactly(
                new EndpointScanPage.Scan(1789725004L, 1789725300L, "COMPLETED", "Completed", 3L, 10L),
                new EndpointScanPage.Scan(1789726000L, null, "IN_PROGRESS", "In Progress", 0L, null),
                new EndpointScanPage.Scan(Instant.parse("2026-09-10T09:15:00Z").getEpochSecond(), null, "TIMED_OUT",
                        "Timed Out", null, null));
        assertThat(page.total()).isEqualTo(7);
        assertThat(serialise(page)).doesNotContain(MARKER);
        assertThat(mapper.toScanPage(parse("{}"), 10, 10).total()).isEqualTo(10);
    }

    @Test
    @DisplayName("file page: a UUID id lower-cased, other ids null; zero and negative times null; Other and the "
            + "structured-data placeholder read as no category")
    void filePageIdsTimesAndOther() {
        EndpointFilePage page = mapper.toFilePage(parse("""
                {"data": [
                  {"id": "0000AAAA-0000-4000-8000-00000000000C", "name": "a.txt", "fileModifiedTime": -62135596800,
                   "updatedAt": 1789725300, "category": "Other", "subcategory": "Other"},
                  {"id": "0000aaaa00004000800000000000000c", "name": "b.txt", "fileModifiedTime": 0, "updatedAt": 0,
                   "category": "Doc Cls Not Supported for Structured Data",
                   "subcategory": "Doc Cls Not Supported for Structured Data"},
                  {"id": 42, "name": "c.txt", "fileModifiedTime": 1789725004, "updatedAt": "2026-09-10T09:15:00Z",
                   "category": "financial", "subcategory": "travel receipts"}]}
                """), 0, 10);
        assertThat(page.rows()).extracting(EndpointFilePage.File::id)
                .containsExactly("0000aaaa-0000-4000-8000-00000000000c", null, null);
        assertThat(page.rows()).extracting(EndpointFilePage.File::modifiedAt).containsExactly(null, null, 1789725004L);
        assertThat(page.rows()).extracting(EndpointFilePage.File::scannedAt)
                .containsExactly(1789725300L, null, Instant.parse("2026-09-10T09:15:00Z").getEpochSecond());
        assertThat(page.rows()).extracting(EndpointFilePage.File::categoryLabel)
                .containsExactly(null, null, "Financial");
        assertThat(page.rows()).extracting(EndpointFilePage.File::documentClassLabel)
                .containsExactly(null, null, "Travel Receipts");
    }
}
