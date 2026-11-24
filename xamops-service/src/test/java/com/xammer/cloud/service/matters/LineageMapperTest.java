package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.dto.matters.datastores.LineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.LineageFilePage;
import com.xammer.cloud.dto.matters.datastores.LineagePolicyPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link LineageMapper} on synthetic JSON shaped by the console bundle's field names. */
class LineageMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LineageMapper mapper = new LineageMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SNAPSHOT = """
            {"success": true, "data": {"filter_options": {
              "categories": [
                {"id": "stale_data", "name": "Stale Data"},
                {"id": "DATA_RESIDENCY", "name": "Data Residency"},
                {"id": " SENSITIVE_FINDINGS ", "name": "Sensitive findings (vendor)"},
                {"id": "UNKNOWN_KIND", "name": "Unknown"},
                {"id": "STALE_DATA", "name": "Duplicate"},
                {"id": "RISKY_USER"},
                {"name": "No id"}
              ],
              "severities": [
                {"id": "high", "name": "HIGH"},
                {"id": "Critical"},
                {"id": "INFO", "name": "Info"},
                {"id": "HIGH", "name": "Again"},
                {"id": 3, "name": "Number"}
              ],
              "datastore_names": [
                {"id": "Team Drive (Gmail)", "name": "Team Drive (Gmail)"},
                {"id": "Lab Laptop"},
                {"id": "Has, comma"},
                {"id": "tab\\tchar"},
                {"id": 42, "name": "Numbered"},
                {"id": "Lab Laptop", "name": "Duplicate"}
              ],
              "labels": [
                {"id": "PII", "name": "PII"},
                {"id": "SECRETS"}
              ]
            }}}
            """;

    @Test
    @DisplayName("snapshot: known categories in enum order, names with the title fallback, unknown and DATA_RESIDENCY dropped")
    void snapshotCategories() {
        LineageFilterSnapshot snapshot = mapper.toSnapshot(json(SNAPSHOT));

        assertThat(snapshot.categoryIds()).containsExactly("SENSITIVE_FINDINGS", "STALE_DATA", "RISKY_USER");
        assertThat(snapshot.categoryNames()).containsExactly(
                Map.entry("SENSITIVE_FINDINGS", "Sensitive findings (vendor)"),
                Map.entry("STALE_DATA", "Stale Data"),
                Map.entry("RISKY_USER", "Risky User"));
    }

    @Test
    @DisplayName("snapshot: severity case and allowed bands, the free-text rule, numeric ids and duplicate values")
    void snapshotOptions() {
        LineageFilterSnapshot snapshot = mapper.toSnapshot(json(SNAPSHOT));

        assertThat(snapshot.severities()).containsExactly(
                new LineageFilterSnapshot.Option("HIGH", "High", "high"),
                new LineageFilterSnapshot.Option("CRITICAL", "Critical", "Critical"));
        assertThat(snapshot.datastoreNames()).containsExactly(
                new LineageFilterSnapshot.Option("Team Drive (Gmail)", "Team Drive (Gmail)", "Team Drive (Gmail)"),
                new LineageFilterSnapshot.Option("Lab Laptop", "Lab Laptop", "Lab Laptop"),
                new LineageFilterSnapshot.Option("42", "Numbered", "42"));
        assertThat(snapshot.labels()).extracting(LineageFilterSnapshot.Option::value).containsExactly("PII", "SECRETS");
        assertThat(snapshot.options(LineageFilters.LABEL)).isSameAs(snapshot.labels());
    }

    @Test
    @DisplayName("snapshot: caps per dimension, and a missing section gives empty lists")
    void snapshotCapsAndMissing() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode options = root.putObject("data").putObject("filter_options");
        ArrayNode names = options.putArray("datastore_names");
        for (int i = 0; i < LineageMapper.MAX_DATASTORE_NAMES + 5; i++) {
            names.addObject().put("id", "store-" + i);
        }
        ArrayNode labels = options.putArray("labels");
        for (int i = 0; i < LineageMapper.MAX_LABELS + 5; i++) {
            labels.addObject().put("id", "LABEL_" + i);
        }
        ArrayNode categories = options.putArray("categories");
        for (int i = 0; i < LineageMapper.MAX_CATEGORIES; i++) {
            categories.addObject().put("id", "OTHER_" + i);
        }
        categories.addObject().put("id", "STALE_DATA");

        LineageFilterSnapshot snapshot = mapper.toSnapshot(root);
        assertThat(snapshot.datastoreNames()).hasSize(LineageMapper.MAX_DATASTORE_NAMES);
        assertThat(snapshot.labels()).hasSize(LineageMapper.MAX_LABELS);
        assertThat(snapshot.categoryIds()).isEmpty();

        LineageFilterSnapshot empty = mapper.toSnapshot(json("{\"success\": true}"));
        assertThat(empty.categoryIds()).isEmpty();
        assertThat(empty.severities()).isEmpty();
        assertThat(empty.datastoreNames()).isEmpty();
        assertThat(empty.labels()).isEmpty();
    }

    @Test
    @DisplayName("categories: one card per snapshot id with its summary count, 0 when missing")
    void categories() {
        LineageFilterSnapshot snapshot = mapper.toSnapshot(json(SNAPSHOT));
        LineageCategories categories = mapper.toCategories(snapshot, Map.of(
                "SENSITIVE_FINDINGS", json("{\"success\": true, \"data\": {\"count\": 4, \"policies\": []}}"),
                "STALE_DATA", json("{\"success\": true, \"data\": {}}")));

        assertThat(categories.available()).isTrue();
        assertThat(categories.categories()).containsExactly(
                new LineageCategories.Category("SENSITIVE_FINDINGS", "Sensitive findings (vendor)", 4L),
                new LineageCategories.Category("STALE_DATA", "Stale Data", 0L),
                new LineageCategories.Category("RISKY_USER", "Risky User", 0L));
    }

    @Test
    @DisplayName("policy page: UUID id lower-cased, severity bands and label, service upper-cased, count")
    void policyPage() {
        LineagePolicyPage page = mapper.toPolicyPage(json("""
                {"success": true, "data": {"count": 12, "policies": [
                  {"ref_policy_id": "0000000A-0000-4000-8000-00000000000B", "policy_name": "Synthetic policy one",
                   "severity": "medium", "target_service": "endpoint", "total_exposures": 3, "extra": "dropped"},
                  {"ref_policy_id": "not-a-uuid", "policy_name": "Synthetic policy two", "severity": "INFO"},
                  {"ref_policy_id": 7}
                ]}}
                """), 7, 7);

        assertThat(page.available()).isTrue();
        assertThat(page.total()).isEqualTo(12);
        assertThat(page.skip()).isEqualTo(7);
        assertThat(page.limit()).isEqualTo(7);
        assertThat(page.rows()).containsExactly(
                new LineagePolicyPage.Policy("0000000a-0000-4000-8000-00000000000b", "Synthetic policy one",
                        "MEDIUM", "Medium", "ENDPOINT", 3L),
                new LineagePolicyPage.Policy(null, "Synthetic policy two", null, "Info", null, null),
                new LineagePolicyPage.Policy(null, null, null, null, null, null));
    }

    @Test
    @DisplayName("datastore page: exposure-id rule, service upper-cased, files count, top-level count")
    void datastorePage() {
        LineageDatastorePage page = mapper.toDatastorePage(json("""
                {"success": true, "count": 2, "data": [
                  {"id": "exposure-1", "datastore_name": "Lab Laptop", "datastore_service": "endpoint",
                   "affected_files_count": 9, "policy_coverage": 1, "ref_saas_asset_id": "x"},
                  {"id": "bad/id", "datastore_name": "Team Drive"},
                  {"id": 12345, "datastore_name": "Numbered"}
                ]}
                """), 0, 7);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).containsExactly(
                new LineageDatastorePage.Datastore("exposure-1", "Lab Laptop", "ENDPOINT", 9L),
                new LineageDatastorePage.Datastore(null, "Team Drive", null, null),
                new LineageDatastorePage.Datastore("12345", "Numbered", null, null));
    }

    @Test
    @DisplayName("file page: id, name, actor and date fallbacks, the zero-time rule, the extension")
    void filePage() {
        LineageFilePage page = mapper.toFilePage(json("""
                {"success": true, "data": {"total_files": 9, "files": [
                  {"id": "FEDCBA9876543210FEDCBA98", "name": "notes/Plan.Final.PDF", "owner": "owner-a",
                   "last_modified_time": 1775000000},
                  {"id": "nope", "file_id": "0000000C-0000-4000-8000-00000000000D", "file_name": "sheet.xlsx",
                   "file_owner": "owner-b", "last_modified_time": 0, "fileModifiedTime": "2026-04-06T11:52:00Z"},
                  {"_id": "aaaaaaaaaaaaaaaaaaaaaaaa", "name": "README", "currentUser": "owner-c",
                   "fileModifiedTime": 1775000000123},
                  {"id": "x", "name": "  ", "last_modified_time": -5}
                ]}}
                """), 0, 7);

        assertThat(page.total()).isEqualTo(9);
        assertThat(page.rows()).containsExactly(
                new LineageFilePage.File("fedcba9876543210fedcba98", "notes/Plan.Final.PDF", "pdf", "owner-a",
                        1775000000L),
                new LineageFilePage.File("0000000c-0000-4000-8000-00000000000d", "sheet.xlsx", "xlsx", "owner-b",
                        1775476320L),
                new LineageFilePage.File("aaaaaaaaaaaaaaaaaaaaaaaa", "README", null, "owner-c", 1775000000L),
                new LineageFilePage.File(null, null, null, null, null));
    }

    @Test
    @DisplayName("missing sections give empty rows and total 0; caps apply")
    void missingAndCaps() {
        JsonNode bare = json("{\"success\": true}");
        assertThat(mapper.toPolicyPage(bare, 0, 7).rows()).isEmpty();
        assertThat(mapper.toPolicyPage(bare, 0, 7).total()).isZero();
        assertThat(mapper.toDatastorePage(json("{\"data\": {}}"), 0, 7).rows()).isEmpty();
        assertThat(mapper.toDatastorePage(bare, 0, 7).total()).isZero();
        assertThat(mapper.toFilePage(json("{\"data\": []}"), 0, 7).rows()).isEmpty();
        assertThat(mapper.toFilePage(null, 0, 7).total()).isZero();

        ObjectNode root = JSON.createObjectNode();
        ArrayNode files = root.putObject("data").putArray("files");
        for (int i = 0; i < LineageMapper.MAX_FILES + 3; i++) {
            files.addObject().put("name", "f" + i);
        }
        assertThat(mapper.toFilePage(root, 0, 100).rows()).hasSize(LineageMapper.MAX_FILES);
    }
}
