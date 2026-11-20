package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFileActivity;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.EndpointFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileListPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6 as changed by plan C1 to C7, C10, C12, C18 and C19, on synthetic bodies shaped like the vendor's.
 * Every value is invented; dropped fields carry {@code MARKER} values.
 */
class FilesPageMapperTest {

    private static final String MARKER = "MARKER";
    private static final String FILE_ID = "0a0b0c0d0e0f101112131415";
    private static final String OWNER_A = "1a1b1c1d1e1f202122232425";
    private static final String OWNER_B = "2a2b2c2d2e2f303132333435";

    private final ObjectMapper json = new ObjectMapper();
    private final FilesPageMapper mapper = new FilesPageMapper();

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

    private static List<String> modes(SaasFileListPage.Row row) {
        return row.sharingModes().stream().map(SaasFileListPage.SharingMode::label).toList();
    }

    private static DatastoreFilterOptions.Dimension dimension(DatastoreFilterOptions options, String key) {
        return options.dimensions().stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> values(DatastoreFilterOptions.Dimension dimension) {
        return dimension.options().stream().map(DatastoreFilterOptions.Option::value).toList();
    }

    private static List<String> labels(DatastoreFilterOptions.Dimension dimension) {
        return dimension.options().stream().map(DatastoreFilterOptions.Option::label).toList();
    }

    // ── SaaS tab ─────────────────────────────────────────────────────────────────────────────────

    private static final String SAAS_PAGE = """
            {"success": true, "sensitives": [
              {"id": "%s", "file_name": "Team offsite agenda", "category": "other", "subcategory": "other",
               "owner": "robin@example.test", "origin": "MARKER-ORIGIN", "last_modified_time": 1789043200,
               "source": "GMAIL", "extension": "Team offsite agenda",
               "share_info": {"externally_shared": {"users": ["MARKER-EXT@example.test"]},
                              "internally_shared": {"users": ["MARKER-INT@example.test"]}, "is_public": false}},
              {"id": "not-hex", "file_name": "Re: notes v2.0 final", "category": "IT & Technical",
               "subcategory": "Travel Receipts", "source": "gmail", "extension": "0 final",
               "last_modified_time": 0,
               "share_info": {"externally_shared": null, "internally_shared": {"users": []}, "is_public": false}},
              {"file_name": "Lunch with the team (example.org)", "source": "GMAIL", "extension": "org)",
               "share_info": {"is_public": true, "externally_shared": true}},
              {"file_name": "export.csv", "source": "GOOGLE_WORKSPACE", "extension": "CSV",
               "share_info": {"externally_shared": null, "internally_shared": null, "is_public": "true"}},
              {"file_name": "no share info", "source": "OUTLOOK"},
              "not-a-row"],
             "previous_cursor": "MARKER-PREVIOUS", "next_cursor": "eyJuZXh0IjoyfQ=="}
            """.formatted(FILE_ID.toUpperCase());

    @Test
    @DisplayName("SaaS page: id, short-extension, label and time rules; sharing modes per C7; cursor kept")
    void saasPage() {
        SaasFileListPage page = mapper.toSaasPage(parse(SAAS_PAGE), 25);
        assertThat(page.limit()).isEqualTo(25);
        assertThat(page.nextCursor()).isEqualTo("eyJuZXh0IjoyfQ==");
        assertThat(page.rows()).hasSize(5);

        SaasFileListPage.Row first = page.rows().get(0);
        assertThat(first).isEqualTo(new SaasFileListPage.Row(FILE_ID, "Team offsite agenda", null, "GMAIL", "Gmail",
                "Other", "other", List.of(new SaasFileListPage.SharingMode("EXTERNAL", "Externally Shared"),
                new SaasFileListPage.SharingMode("INTERNAL", "Internally Shared")), "robin@example.test", 1789043200L));

        assertThat(page.rows()).extracting(SaasFileListPage.Row::id).containsExactly(FILE_ID, null, null, null, null);
        assertThat(page.rows()).extracting(SaasFileListPage.Row::extension)
                .containsExactly(null, null, null, "csv", null);
        assertThat(page.rows()).extracting(SaasFileListPage.Row::source)
                .containsExactly("GMAIL", "GMAIL", "GMAIL", "GOOGLE_WORKSPACE", "OUTLOOK");
        assertThat(page.rows()).extracting(SaasFileListPage.Row::sourceLabel)
                .containsExactly("Gmail", "Gmail", "Gmail", "Google Drive", "Outlook");
        assertThat(page.rows().get(1).categoryLabel()).isEqualTo("IT & Technical");
        assertThat(page.rows().get(1).documentClassLabel()).isEqualTo("Travel Receipts");
        assertThat(page.rows().get(1).lastModifiedAt()).isNull();
        assertThat(page.rows()).extracting(FilesPageMapperTest::modes).containsExactly(
                List.of("Externally Shared", "Internally Shared"),
                List.of("Internally Shared"),
                List.of("Anyone with the link", "Externally Shared"),
                List.of("Private"),
                List.of("Private"));

        String serialised = serialise(page);
        assertThat(serialised).doesNotContain(MARKER).doesNotContain("origin").doesNotContain("previous")
                .doesNotContain("users");
    }

    @Test
    @DisplayName("SaaS page: a malformed or missing next cursor is dropped; an empty body gives no rows")
    void saasCursor() {
        assertThat(mapper.toSaasPage(parse("{\"sensitives\": [], \"next_cursor\": \"a b\"}"), 10).nextCursor()).isNull();
        assertThat(mapper.toSaasPage(parse("{\"next_cursor\": \"" + "c".repeat(1025) + "\"}"), 10).nextCursor())
                .isNull();
        assertThat(mapper.toSaasPage(parse("{\"next_cursor\": null}"), 10).nextCursor()).isNull();
        SaasFileListPage empty = mapper.toSaasPage(parse("{}"), 10);
        assertThat(empty.rows()).isEmpty();
        assertThat(mapper.sharingModes(parse("{}").path("share_info")))
                .containsExactly(new SaasFileListPage.SharingMode("PRIVATE", "Private"));
    }

    private static final String CATEGORIES = """
            {"success": true, "categories": [
              {"category": "financial", "subcategories": ["travel receipts", "Invoices", "travel receipts"]},
              {"category": "Media R&D", "subcategories": ["Field Notes", "Invoices"]},
              {"category": null, "subcategories": ["QA and Release"]},
              {"category": "financial", "subcategories": ["budget plans"]}]}
            """;

    private static String users(int externalUsers) {
        String external = IntStream.range(0, externalUsers).mapToObj(i -> "\"ext" + i + "@example.org\"")
                .collect(Collectors.joining(","));
        return """
                {"success": true,
                 "owners": [{"key": "robin@example.test", "value": "%s"},
                            {"key": "robin@example.test", "value": "%s"},
                            {"key": "duplicate@example.test", "value": "%s"},
                            {"key": "skipped@example.test", "value": "MARKER-NOT-HEX"},
                            {"value": "%s"}],
                 "internal_users": ["sam@example.test", "sam@example.test", ""],
                 "external_users": [%s]}
                """.formatted(OWNER_A.toUpperCase(), OWNER_B, OWNER_A, "3a3b3c3d3e3f404142434445", external);
    }

    @Test
    @DisplayName("SaaS options: dimension order, the fixed lists, classes de-duplicated in route order, owners per id")
    void saasOptions() {
        SaasFileOptionsEntry entry = mapper.toSaasOptions(parse(CATEGORIES), parse(users(2)));
        DatastoreFilterOptions options = entry.options();
        assertThat(options.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly(
                "sensitives", "category", "appType", "documentClass", "owner", "internalUser", "externalUser",
                "sharing");
        assertThat(options.dimensions()).allSatisfy(d ->
                assertThat(d.options()).allSatisfy(o -> assertThat(o.count()).isNull()));

        assertThat(values(dimension(options, "sensitives"))).containsExactly("PII", "PCI", "PHI", "SECRETS");
        assertThat(labels(dimension(options, "sensitives")))
                .containsExactly("Personal", "Financial", "Health", "IT & Security");
        assertThat(values(dimension(options, "category"))).containsExactly("financial", "Media R&D");
        assertThat(labels(dimension(options, "category"))).containsExactly("Financial", "Media R&D");
        assertThat(values(dimension(options, "appType"))).containsExactly("ONE_DRIVE", "SHARE_POINT", "OUTLOOK",
                "GOOGLE_WORKSPACE", "GMAIL", "JIRA", "GITLAB", "CONFLUENCE", "SLACK");
        assertThat(labels(dimension(options, "appType"))).containsExactly("OneDrive", "SharePoint", "Outlook",
                "Google Drive", "Gmail", "Jira", "Gitlab", "Confluence", "Slack");
        assertThat(values(dimension(options, "documentClass")))
                .containsExactly("travel receipts", "Invoices", "Field Notes", "QA and Release", "budget plans");
        assertThat(labels(dimension(options, "documentClass")))
                .containsExactly("Travel Receipts", "Invoices", "Field Notes", "QA And Release", "Budget Plans");
        assertThat(dimension(options, "owner").options()).containsExactly(
                new DatastoreFilterOptions.Option(OWNER_A, "robin@example.test", null),
                new DatastoreFilterOptions.Option(OWNER_B, "robin@example.test", null),
                new DatastoreFilterOptions.Option("3a3b3c3d3e3f404142434445", "3a3b3c3d3e3f404142434445", null));
        assertThat(values(dimension(options, "internalUser"))).containsExactly("sam@example.test");
        assertThat(labels(dimension(options, "externalUser"))).containsExactly("ext0@example.org", "ext1@example.org");
        assertThat(values(dimension(options, "sharing"))).containsExactly("internal", "external", "public");
        assertThat(labels(dimension(options, "sharing")))
                .containsExactly("Shared Internally", "Shared Externally", "Shared Publicly");

        assertThat(entry.classesByCategory()).containsExactly(
                org.assertj.core.api.Assertions.entry("financial", List.of("travel receipts", "Invoices", "budget plans")),
                org.assertj.core.api.Assertions.entry("Media R&D", List.of("Field Notes", "Invoices")));
        assertThat(serialise(options)).doesNotContain(MARKER).doesNotContain("duplicate@");
    }

    @Test
    @DisplayName("SaaS options: 501 external users give 500 and hasMore; missing bodies give empty lists")
    void saasOptionsCaps() {
        DatastoreFilterOptions options = mapper.toSaasOptions(parse(CATEGORIES), parse(users(501))).options();
        assertThat(dimension(options, "externalUser").options()).hasSize(500);
        assertThat(dimension(options, "externalUser").hasMore()).isTrue();
        assertThat(dimension(options, "internalUser").hasMore()).isFalse();

        SaasFileOptionsEntry empty = mapper.toSaasOptions(parse("{}"), parse("{}"));
        assertThat(dimension(empty.options(), "category").options()).isEmpty();
        assertThat(dimension(empty.options(), "owner").options()).isEmpty();
        assertThat(dimension(empty.options(), "sensitives").options()).hasSize(4);
        assertThat(empty.classesByCategory()).isEmpty();
    }

    // ── Endpoint tab ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Endpoint options: C6 order, the placeholder dropped, data elements in sentence case, users unread")
    void endpointOptions() {
        String uids = IntStream.range(0, 501).mapToObj(i -> "\"UID-" + i + "\"").collect(Collectors.joining(","));
        DatastoreFilterOptions options = mapper.toEndpointOptions(parse("""
                {"success": true, "data": {
                  "uids": [%s], "hostnames": ["Team-Laptop-02"], "users": ["MARKER-USER"],
                  "categories": ["financial", "Doc Cls Not Supported for Structured Data", "other"],
                  "subcategories": ["Doc Cls Not Supported for Structured Data", "travel receipts"],
                  "sensitives": ["MARKER"], "dataElements": ["EMAIL ADDRESS", "BADGE NUMBER"],
                  "tags": ["Reviewed"]}}
                """.formatted(uids)));
        assertThat(options.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly(
                "category", "sensitives", "documentClass", "dataElement", "tag", "uid", "hostname", "lastModified");
        assertThat(labels(dimension(options, "category"))).containsExactly("financial", "other");
        assertThat(values(dimension(options, "sensitives"))).containsExactly("PII", "PCI", "PHI", "SECRETS");
        assertThat(values(dimension(options, "documentClass"))).containsExactly("travel receipts");
        assertThat(dimension(options, "dataElement").options()).containsExactly(
                new DatastoreFilterOptions.Option("EMAIL ADDRESS", "Email address", null),
                new DatastoreFilterOptions.Option("BADGE NUMBER", "Badge number", null));
        assertThat(values(dimension(options, "tag"))).containsExactly("Reviewed");
        assertThat(dimension(options, "uid").options()).hasSize(500);
        assertThat(dimension(options, "uid").hasMore()).isTrue();
        assertThat(dimension(options, "hostname").hasMore()).isFalse();
        assertThat(labels(dimension(options, "lastModified")))
                .containsExactly("Last 48 hours", "Last 7 days", "Last 30 days");
        assertThat(serialise(options)).doesNotContain(MARKER).doesNotContain("Doc Cls");
    }

    // ── Endpoint file drawer ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Endpoint file overview: whitelisted fields, chip-case category, times, sensitives; no sample")
    void endpointFileOverview() {
        EndpointFileOverview overview = mapper.toEndpointFileOverview(parse("""
                {"success": true, "overview": {
                  "id": "MARKER-ID", "name": "notes.MSG", "extension": "MSG", "path": "/Users/synthetic/notes.MSG",
                  "size": 412, "category": "MARKER-CATEGORY", "category_label": "it & technical",
                  "subcategory": "MARKER-SUBCATEGORY", "subcategory_label": "Travel Receipts",
                  "labels": ["MARKER-LABEL"], "device": {"agent_id": "MARKER-AGENT", "hostname": "Team-Laptop-02"},
                  "owner": "robin", "scan_status": "MARKER-SCAN", "last_modified_time": 1788264000,
                  "file_created_time": -62135596800, "file_accessed_time": 0, "created_at": 1700000003,
                  "sensitive_entities_count": 3,
                  "sensitives": [{"data_element": "BADGE NUMBER", "count": 2, "label": ["PII"],
                                  "is_false_positive": false},
                                 {"count": 9, "label": ["PCI"]},
                                 {"data_element": "BANK ACCOUNT", "label": ["PCI"]}],
                  "sample_matches": [{"name": "BADGE NUMBER", "label": "PII",
                                      "samples": [{"match_text": "MARKER-SAMPLE", "line_no": null}]}],
                  "tags": ["Reviewed"], "is_synced": false, "pending_agent_sync": false, "is_deleted": false}}
                """));
        assertThat(overview).isEqualTo(new EndpointFileOverview("notes.MSG", "msg", "robin", "It & Technical",
                "Travel Receipts", 3, "/Users/synthetic/notes.MSG", 412L, "Team-Laptop-02", 1788264000L, null, null,
                List.of("Reviewed"), List.of(new EndpointFileOverview.Sensitive("BADGE NUMBER", "Personal", 2),
                new EndpointFileOverview.Sensitive("BANK ACCOUNT", "Financial", 0))));
        assertThat(serialise(overview)).doesNotContain(MARKER).doesNotContain("1700000003");
    }

    @Test
    @DisplayName("Endpoint file overview: fallbacks to category, subcategory and the name's extension; empty body")
    void endpointFileOverviewFallbacks() {
        EndpointFileOverview overview = mapper.toEndpointFileOverview(parse("""
                {"overview": {"name": "/tmp/report.PDF", "category": "other", "subcategory": "other"}}
                """));
        assertThat(overview.extension()).isEqualTo("pdf");
        assertThat(overview.categoryLabel()).isEqualTo("Other");
        assertThat(overview.documentClassLabel()).isEqualTo("other");
        assertThat(overview.sensitiveRecords()).isZero();
        EndpointFileOverview empty = mapper.toEndpointFileOverview(parse("{}"));
        assertThat(empty.name()).isNull();
        assertThat(empty.tags()).isEmpty();
        assertThat(empty.sensitives()).isEmpty();
    }

    @Test
    @DisplayName("lineage asset: title, device, OS user and first seen; events mapped with no lane; ids, path, "
            + "edges and alerts never read")
    void lineageAsset() {
        EndpointFileLineage.Asset asset = mapper.toEndpointLineageAsset(parse("""
                {"success": true, "data": {
                  "asset": {"file_id": "MARKER-FILE", "doc_id": "MARKER-DOC", "title": "notes.docx",
                            "doc_type": "docx", "path": "/MARKER-PATH/notes.docx", "size_bytes": 412,
                            "os_user": "robin", "first_seen": "2026-09-10T09:15:00+00:00",
                            "device": "Team-Laptop-02", "resource_id": "MARKER-RESOURCE", "owner_email": null},
                  "events": [
                    {"event_type": "FILE_RENAMED", "actor_email": "robin@example.test", "timestamp": 1789725004},
                    {"type": "deleted", "os_user": "robin", "occurred_at": "2026-09-12T10:00:00Z"},
                    {"actor": "sam", "created_at": 1789725004377},
                    "not-an-event"],
                  "edges": [{"from": "MARKER-EDGE"}], "alerts": [{"id": "MARKER-ALERT"}]}}
                """));
        assertThat(asset).isEqualTo(new EndpointFileLineage.Asset("notes.docx", "Team-Laptop-02", "robin",
                Instant.parse("2026-09-10T09:15:00Z").getEpochSecond(), List.of(
                new SaasFileLineage.Event(null, "File Renamed", "robin@example.test", 1789725004L),
                new SaasFileLineage.Event(null, "Deleted", "robin", Instant.parse("2026-09-12T10:00:00Z")
                        .getEpochSecond()),
                new SaasFileLineage.Event(null, null, "sam", 1789725004L))));
        assertThat(serialise(asset)).doesNotContain(MARKER);

        assertThat(mapper.toEndpointLineageAsset(parse("{\"success\": true, \"data\": {\"events\": []}}"))).isNull();
        assertThat(mapper.toEndpointLineageAsset(parse("{\"data\": {\"asset\": null}}"))).isNull();
        assertThat(mapper.toEndpointLineageAsset(parse("{}"))).isNull();
        assertThat(mapper.toEndpointLineageAsset(parse("{\"data\": {\"asset\": {}}}")))
                .isEqualTo(new EndpointFileLineage.Asset(null, null, null, null, List.of()));
    }

    @Test
    @DisplayName("MIP activity: API order, labels, tags, flags only for boolean true; empty items dropped")
    void activity() {
        EndpointFileActivity activity = mapper.toEndpointActivity(parse("""
                {"success": true, "audit_logs": [
                  {"action": "tag_changed", "timestamp": 1789725004, "performed_by": "robin@example.test",
                   "selected_tag": {"name": "Confidential", "id": "MARKER-TAG"}, "previous_tag": {"name": "General"},
                   "keywords_written": true, "mip_label_written": "true", "file_id": "MARKER-FILE"},
                  {"action": "TAG_SKIPPED", "timestamp": "2026-09-12T10:00:00Z", "selected_tag": {"name": "%s"},
                   "previous_tag": null, "keywords_written": false},
                  {"performed_by": "nobody@example.test"},
                  {"action": "LABEL_SYNCED"},
                  {"timestamp": 1789725100}]}
                """.formatted("t".repeat(129))));
        assertThat(activity.entries()).containsExactly(
                new EndpointFileActivity.Entry("TAG_CHANGED", "Tag changed", "Confidential", "General",
                        "robin@example.test", 1789725004L, true, false),
                new EndpointFileActivity.Entry("TAG_SKIPPED", "Tag skipped", null, null, null,
                        Instant.parse("2026-09-12T10:00:00Z").getEpochSecond(), false, false),
                new EndpointFileActivity.Entry("LABEL_SYNCED", "LABEL_SYNCED", null, null, null, null, false, false),
                new EndpointFileActivity.Entry(null, null, null, null, null, 1789725100L, false, false));
        assertThat(serialise(activity)).doesNotContain(MARKER);
        assertThat(mapper.toEndpointActivity(parse("{\"success\": true, \"audit_logs\": []}")).entries()).isEmpty();
        assertThat(mapper.toEndpointActivity(parse("{}")).entries()).isEmpty();
    }
}
