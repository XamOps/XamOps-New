package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage.Datastore;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage.File;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build 7 design 5.5 on synthetic JSON that uses the captured responses' field names, never their values.
 */
class PublicLineageMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String HEX = "0123456789abcdef01234567";
    private static final String HEX2 = "fedcba9876543210fedcba98";
    private static final String UUID = "0000000c-0000-4000-8000-00000000000d";
    private static final String RESOURCE = "0000000e-0000-4000-8000-00000000000f";

    private final PublicLineageMapper mapper = new PublicLineageMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode dataOf(ArrayNode rows) {
        ObjectNode body = NODES.objectNode();
        body.put("success", true);
        body.set("data", rows);
        return body;
    }

    @Test
    @DisplayName("endpointRows: 24-hex source_id lower-cased with the name; a bad or missing source_id is dropped; cap 100")
    void endpointRows() {
        JsonNode body = json("""
                {"data": [
                  {"source_id": "%s", "name": "Lab Laptop", "category": "endpoint"},
                  {"source_id": "not-an-agent", "name": "Dropped"},
                  {"name": "No id"},
                  {"source_id": "%s"},
                  "not an object"
                ], "pagination": {"total": 5}}
                """.formatted(HEX.toUpperCase(), HEX2));

        assertThat(mapper.endpointRows(body)).containsExactly(
                new PublicLineageMapper.EndpointRef(HEX, "Lab Laptop"),
                new PublicLineageMapper.EndpointRef(HEX2, null));

        ArrayNode many = NODES.arrayNode();
        for (int i = 0; i < 120; i++) {
            many.addObject().put("source_id", String.format("%024x", i)).put("name", "E" + i);
        }
        assertThat(mapper.endpointRows(dataOf(many))).hasSize(100);
    }

    @Test
    @DisplayName("fileTotal: total, else 0")
    void fileTotal() {
        assertThat(mapper.fileTotal(json("{\"success\": true, \"total\": 42, \"data\": []}"))).isEqualTo(42);
        assertThat(mapper.fileTotal(json("{\"success\": true, \"total\": \"42\"}"))).isZero();
        assertThat(mapper.fileTotal(json("{}"))).isZero();
        assertThat(mapper.fileTotal(null)).isZero();
    }

    @Test
    @DisplayName("integrationIds: 24-hex values lower-cased, bad and duplicates dropped, API order, cap 10")
    void integrationIds() {
        JsonNode body = json("""
                {"integration_id": [{"value": "%s", "count": 3}, {"value": "bad"}, {"value": "%s"},
                                    {"value": "%s"}, {"count": 1}, {"value": 7}],
                 "provider": [{"value": "%s"}]}
                """.formatted(HEX2, HEX.toUpperCase(), HEX2, "abcdefabcdefabcdefabcdef"));

        assertThat(mapper.integrationIds(body)).containsExactly(HEX2, HEX);

        ArrayNode many = NODES.arrayNode();
        for (int i = 0; i < 15; i++) {
            many.addObject().put("value", String.format("%024x", i));
        }
        ObjectNode capped = NODES.objectNode();
        capped.set("integration_id", many);
        assertThat(mapper.integrationIds(capped)).hasSize(10).first().isEqualTo(String.format("%024x", 0));
        assertThat(mapper.integrationIds(json("{}"))).isEmpty();
    }

    @Test
    @DisplayName("service: Google codes map to GOOGLE_DRIVE, OneDrive and SharePoint kept, others upper-cased, none null")
    void service() {
        String sample = "{\"data\": [{\"source\": {\"provider\": \"%s\", \"account\": \"a\"}}]}";
        assertThat(mapper.service(json(sample.formatted("GOOGLE_WORKSPACE")))).isEqualTo("GOOGLE_DRIVE");
        assertThat(mapper.service(json(sample.formatted("google_drive")))).isEqualTo("GOOGLE_DRIVE");
        assertThat(mapper.service(json(sample.formatted("ONE_DRIVE")))).isEqualTo("ONE_DRIVE");
        assertThat(mapper.service(json(sample.formatted("share_point")))).isEqualTo("SHARE_POINT");
        assertThat(mapper.service(json(sample.formatted("slack")))).isEqualTo("SLACK");
        assertThat(mapper.service(json(sample.formatted("  ")))).isNull();
        assertThat(mapper.service(json("{\"data\": [{\"source\": {}}]}"))).isNull();
        assertThat(mapper.service(json("{\"data\": []}"))).isNull();
        assertThat(mapper.service(json("{}"))).isNull();
    }

    @Test
    @DisplayName("people: file_count above 0 kept, name falls back to email, a bad resource id gives a null id, cap 200")
    void people() {
        JsonNode body = json("""
                {"success": true, "data": [
                  {"resource_id": "%s", "email": "a@example.com", "display_name": "Person A", "file_count": 4,
                   "is_external": 0},
                  {"resource_id": "r-zero", "email": "z@example.com", "display_name": "Zero", "file_count": 0},
                  {"resource_id": "r-none", "email": "n@example.com"},
                  {"resource_id": "Res_B", "email": "b@example.com", "file_count": 2},
                  {"resource_id": "bad/id", "display_name": "Person C", "file_count": 1}
                ]}
                """.formatted(RESOURCE));

        assertThat(mapper.people(body, HEX, "GOOGLE_DRIVE")).containsExactly(
                new Datastore("dr-" + HEX + "-" + RESOURCE, "DRIVE", "Person A", "GOOGLE_DRIVE", 4L),
                new Datastore("dr-" + HEX + "-Res_B", "DRIVE", "b@example.com", "GOOGLE_DRIVE", 2L),
                new Datastore(null, "DRIVE", "Person C", "GOOGLE_DRIVE", 1L));
        assertThat(mapper.people(body, HEX, null)).extracting(Datastore::service).containsOnlyNulls();

        ArrayNode many = NODES.arrayNode();
        for (int i = 0; i < 250; i++) {
            many.addObject().put("resource_id", "r" + i).put("display_name", "P" + i).put("file_count", 1);
        }
        assertThat(mapper.people(dataOf(many), HEX, null)).hasSize(200);
        assertThat(mapper.people(json("{}"), HEX, null)).isEmpty();
    }

    @Test
    @DisplayName("endpoint files: UUID rule, extension rule, actor, dates, zero and negative time null, total; cap 100")
    void endpointFiles() {
        JsonNode body = json("""
                {"success": true, "total": 9, "data": [
                  {"id": "%s", "name": "notes.final.PDF", "extension": "PDF", "currentUser": "user-a",
                   "fileModifiedTime": 1775000100, "fileCreatedTime": 1775000000, "path": "/x/notes.final.PDF",
                   "sampleMatches": [{"samples": [{"match_text": "secret"}]}]},
                  {"id": "not-a-uuid", "name": "Report.Docx", "extension": "very long extension",
                   "fileModifiedTime": 0, "fileCreatedTime": -62135596800},
                  {"name": "plain", "fileModifiedTime": 1775000200000}
                ]}
                """.formatted(UUID.toUpperCase()));

        PublicLineageFilePage page = mapper.toEndpointFiles(body, 7, 7);

        assertThat(page.total()).isEqualTo(9);
        assertThat(page.skip()).isEqualTo(7);
        assertThat(page.limit()).isEqualTo(7);
        assertThat(page.rows()).containsExactly(
                new File(UUID, "notes.final.PDF", "pdf", "user-a", 1775000100L, 1775000000L),
                new File(null, "Report.Docx", "docx", null, null, null),
                new File(null, "plain", null, null, 1775000200L, null));

        ArrayNode many = NODES.arrayNode();
        for (int i = 0; i < 130; i++) {
            many.addObject().put("id", UUID).put("name", "f" + i + ".txt");
        }
        assertThat(mapper.toEndpointFiles(dataOf(many), 0, 100).rows()).hasSize(100);
        assertThat(mapper.toEndpointFiles(json("{}"), 0, 7)).satisfies(empty -> {
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.total()).isZero();
        });
    }

    @Test
    @DisplayName("drive files: doc id kept as is, doc_type map, dateAt from last_seen, no actor or createdAt; cap 1,000")
    void driveFiles() {
        JsonNode body = json("""
                {"success": true, "data": [
                  {"doc_id": "Doc_Synthetic-A", "title": "Budget.XLSX", "doc_type": "Microsoft Excel",
                   "visibility": "private", "size_bytes": 10, "last_seen": "2026-11-01T10:00:00+00:00", "event_count": 3},
                  {"doc_id": "Doc_Synthetic-B", "title": "Plan", "doc_type": "Microsoft Word", "last_seen": "2026-11-02T00:00:00Z"},
                  {"doc_id": "Doc_Synthetic-C", "title": "Deck", "doc_type": "microsoft powerpoint"},
                  {"doc_id": "Doc_Synthetic-D", "title": "Scan", "doc_type": "PDF"},
                  {"doc_id": "bad/doc", "title": "Odd.verylongextension", "doc_type": "Google Docs"},
                  {"doc_id": "%s", "title": "Shaped like a UUID"},
                  {"title": "No id", "last_seen": "0001-01-01T00:00:00Z"}
                ]}
                """.formatted(UUID));

        List<File> rows = mapper.toDriveFiles(body);

        assertThat(rows).containsExactly(
                new File("Doc_Synthetic-A", "Budget.XLSX", "xlsx", null, 1793527200L, null),
                new File("Doc_Synthetic-B", "Plan", "docx", null, 1793577600L, null),
                new File("Doc_Synthetic-C", "Deck", "pptx", null, null, null),
                new File("Doc_Synthetic-D", "Scan", "pdf", null, null, null),
                new File(null, "Odd.verylongextension", null, null, null, null),
                new File(null, "Shaped like a UUID", null, null, null, null),
                new File(null, "No id", null, null, null, null));

        ArrayNode many = NODES.arrayNode();
        for (int i = 0; i < 1_200; i++) {
            many.addObject().put("doc_id", "d" + i).put("title", "t" + i);
        }
        assertThat(mapper.toDriveFiles(dataOf(many))).hasSize(1_000);
        assertThat(mapper.toDriveFiles(json("{\"success\": true}"))).isEmpty();
    }

    @Test
    @DisplayName("driveOwner: data.asset.owner_email up to 320 characters, else null")
    void driveOwner() {
        assertThat(mapper.driveOwner(json("""
                {"success": true, "data": {"asset": {"doc_id": "Doc_Synthetic-A", "owner_email": " owner@example.com "},
                 "events": [], "edges": [], "alerts": []}}
                """))).isEqualTo("owner@example.com");
        String long321 = "a".repeat(309) + "@example.com";
        assertThat(long321).hasSize(321);
        assertThat(mapper.driveOwner(json("{\"data\": {\"asset\": {\"owner_email\": \"" + long321 + "\"}}}"))).isNull();
        assertThat(mapper.driveOwner(json("{\"data\": {\"asset\": {\"owner_email\": 5}}}"))).isNull();
        assertThat(mapper.driveOwner(json("{\"data\": {}}"))).isNull();
        assertThat(mapper.driveOwner(json("{}"))).isNull();
    }

    @Test
    @DisplayName("missing sections give empty lists and nulls, never an exception")
    void missingSections() {
        JsonNode empty = json("{}");
        JsonNode wrong = json("{\"data\": {\"not\": \"an array\"}, \"integration_id\": \"x\"}");

        assertThat(mapper.endpointRows(empty)).isEmpty();
        assertThat(mapper.endpointRows(wrong)).isEmpty();
        assertThat(mapper.integrationIds(wrong)).isEmpty();
        assertThat(mapper.people(wrong, HEX, null)).isEmpty();
        assertThat(mapper.toEndpointFiles(wrong, 0, 7).rows()).isEmpty();
        assertThat(mapper.toDriveFiles(wrong)).isEmpty();
        assertThat(mapper.service(wrong)).isNull();
        assertThat(mapper.driveOwner(wrong)).isNull();
    }
}
