package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.matters.datastores.SaasDrawerHeader;
import com.xammer.cloud.dto.matters.datastores.SaasFileActivity;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import com.xammer.cloud.dto.matters.datastores.SaasFileOverview;
import com.xammer.cloud.dto.matters.datastores.SaasFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasRecords;
import com.xammer.cloud.dto.matters.datastores.SaasScanSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6.1 on synthetic bodies shaped like the vendor's. Every field the mapper must drop carries a
 * {@code MARKER} value, and the serialised DTOs are checked for it (AC19).
 */
class SaasDrawerMapperTest {

    private static final String MARKER = "MARKER";
    private static final String GMAIL_PDF = "a1b2c3d4e5f6a7b8c9d0e1f2";
    private static final String DRIVE_DOCX = "b1b2c3d4e5f6a7b8c9d0e1f2";
    private static final String DRIVE_COPY = "c1b2c3d4e5f6a7b8c9d0e1f2";
    private static final String DRIVE_PDF = "d1b2c3d4e5f6a7b8c9d0e1f2";

    private final ObjectMapper json = new ObjectMapper();
    private final SaasDrawerMapper mapper = new SaasDrawerMapper();

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

    private static long epoch(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    // ── SaaS drawer ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header: drive type and scan states upper-cased with labels; name, ids and owner are not read")
    void header() {
        SaasDrawerHeader header = mapper.toHeader(parse("""
                {"success": true, "detail": {"id": "MARKER-ID", "owner": "MARKER-OWNER", "name": "MARKER-NAME",
                  "drive_type": "gmail", "account_name": " ops-mailbox ", "asset_type": "MARKER-TYPE",
                  "saas_integration_id": "MARKER-INTEGRATION",
                  "source": {"name": "MARKER-SOURCE", "provider": "MARKER-PROVIDER"}, "datastore_size": 10,
                  "discovery_state": "MARKER-DISCOVERY", "realtime_scan_status": "enabled", "scan_state": "COMPLETED",
                  "discovery": {"failure_reason": "MARKER-FAILURE"}, "scanning": {"failure_reason": "MARKER-FAILURE"}}}
                """));
        assertThat(header).isEqualTo(new SaasDrawerHeader("GMAIL", "Gmail", "ops-mailbox",
                "COMPLETED", "Completed", "ENABLED", "Enabled"));
        assertThat(serialise(header)).doesNotContain(MARKER);

        SaasDrawerHeader drive = mapper.toHeader(parse("""
                {"detail": {"drive_type": "GOOGLE_WORKSPACE", "source": {"name": "Drive Team"},
                  "scan_state": "IN_PROGRESS"}}
                """));
        assertThat(drive).isEqualTo(new SaasDrawerHeader("GOOGLE_WORKSPACE", "Google Drive", "Drive Team",
                "IN_PROGRESS", "In Progress", null, null));
    }

    @Test
    @DisplayName("records: API order, blank names dropped, the category from the first label code")
    void dataElementRecords() {
        SaasRecords records = mapper.toRecords(parse("""
                {"success": true, "view": "data_elements", "records": [
                  {"sensitive_record": "EMAIL ADDRESS", "label": ["PII"], "count": 40},
                  {"sensitive_record": "  ", "label": ["PII"], "count": 9},
                  {"sensitive_record": "BANK ACCOUNT", "label": ["pci", "PII"], "count": 12},
                  {"sensitive_record": "API KEY", "label": [], "count": 12},
                  {"label": ["PII"], "count": 3}]}
                """), "data_elements");
        assertThat(records.view()).isEqualTo("data_elements");
        assertThat(records.rows()).containsExactly(
                new SaasRecords.Row("EMAIL ADDRESS", "Personal", 40),
                new SaasRecords.Row("BANK ACCOUNT", "Financial", 12),
                new SaasRecords.Row("API KEY", null, 12));
    }

    @Test
    @DisplayName("document classes: the subcategory label is the name and the category label is kept as returned")
    void documentClassRecords() {
        SaasRecords records = mapper.toRecords(parse("""
                {"success": true, "view": "document_classes", "records": [
                  {"category_label": "IT & Technical", "subcategory_label": "Incident Reports", "count": 30},
                  {"category_label": "Financial", "subcategory_label": null, "count": 3},
                  {"category_label": "Financial", "subcategory_label": "Budget Plans", "count": 8}]}
                """), "document_classes");
        assertThat(records.rows()).containsExactly(
                new SaasRecords.Row("Incident Reports", "IT & Technical", 30),
                new SaasRecords.Row("Budget Plans", "Financial", 8));
        assertThat(mapper.toRecords(parse("{}"), "document_classes").rows()).isEmpty();
    }

    @Test
    @DisplayName("file page: 24-hex ids lower-cased, any other id null; total from count, else skip + rows")
    void filePage() {
        String files = """
                {"success": true, "files": [
                  {"id": "ABCDEF0123456789ABCDEF01", "file": "Plan Draft.pdf", "records": 4, "sampled_data": "MARKER"},
                  {"id": "not-a-hex-id", "file": "Old Notes.txt", "records": 1},
                  {"file": "No Id.csv"}]%s}
                """;
        SaasFilePage page = mapper.toFilePage(parse(files.formatted(", \"count\": 12")), 5, 5);
        assertThat(page.rows()).containsExactly(
                new SaasFilePage.File("abcdef0123456789abcdef01", "Plan Draft.pdf", 4),
                new SaasFilePage.File(null, "Old Notes.txt", 1),
                new SaasFilePage.File(null, "No Id.csv", 0));
        assertThat(page.total()).isEqualTo(12);
        assertThat(page.skip()).isEqualTo(5);
        assertThat(page.limit()).isEqualTo(5);
        assertThat(serialise(page)).doesNotContain(MARKER);
        assertThat(mapper.toFilePage(parse(files.formatted("")), 5, 5).total()).isEqualTo(8);
    }

    @Test
    @DisplayName("scan summary: file types by count, then label, then key; unknown extensions upper-cased")
    void scanSummary() {
        SaasScanSummary summary = mapper.toScanSummary(parse("""
                {"success": true, "scan_summary": {
                  "supported": {"count": 17230, "size": 987654321}, "unsupported": {"count": 12},
                  "file_type_breakdown": {"pdf": 30, "msg": 16000, "png": 1200, "xlsx": 30, "csv": "7", "abc": 30},
                  "properties": {"owner": " Robin Vale ", "datastore_size": 123456, "created_on": 1789000000,
                                 "last_scan": 1789725004377}}}
                """));
        assertThat(summary.supported()).isEqualTo(new SaasScanSummary.Part(17230, 987654321));
        assertThat(summary.unsupported()).isEqualTo(new SaasScanSummary.Part(12, 0));
        assertThat(summary.fileTypes()).containsExactly(
                new SaasScanSummary.FileType("msg", "Email", 16000),
                new SaasScanSummary.FileType("png", "Images", 1200),
                new SaasScanSummary.FileType("abc", "ABC", 30),
                new SaasScanSummary.FileType("pdf", "PDF", 30),
                new SaasScanSummary.FileType("xlsx", "XLSX", 30));
        assertThat(summary.properties()).isEqualTo(
                new SaasScanSummary.Properties("Robin Vale", 123456L, 1789000000L, 1789725004L));

        SaasScanSummary empty = mapper.toScanSummary(parse("{}"));
        assertThat(empty.fileTypes()).isEmpty();
        assertThat(empty.supported()).isEqualTo(new SaasScanSummary.Part(0, 0));
        assertThat(empty.properties()).isEqualTo(new SaasScanSummary.Properties(null, null, null, null));
    }

    // ── SaaS file drawer: overview ───────────────────────────────────────────────────────────────

    private static final String GMAIL_OVERVIEW = """
            {"success": true, "overview": {
              "id": "MARKER-ID", "file_id": "MARKER-FILEID", "name": "Quarterly Plan.pdf", "file_extension": "PDF",
              "path": "MARKER-PATH", "source": {"drive_type": "gmail", "account_name": "ops-mailbox"},
              "owner": "robin@example.test", "category": "MARKER-CATEGORY", "category_label": "Financial",
              "subcategory": "MARKER-SUBCATEGORY", "subcategory_label": "Budget Plans",
              "labels": ["MARKER-LABEL"], "mip_labels": ["MARKER-MIP"], "size": 121863, "is_structured": false,
              "last_modified_time": 1789725004, "last_modified_by": "robin@example.test", "created_at": 1700000000,
              "sensitive_entities_count": 7, "policy_violation_count": 2, "sampled_data": ["MARKER-SAMPLE"],
              "sharing": {"is_public": false, "is_private": true, "is_external_share": "true", "is_org_wide": false,
                "external_emails": [], "external_groups": [],
                "internal_emails": ["robin@example.test", "sam@example.test"], "internal_groups": []},
              "sensitives": [
                {"data_element": "EMAIL ADDRESS", "count": 5, "label": ["PII"], "sampled_data": ["MARKER-SAMPLE"]},
                {"data_element": "BANK ACCOUNT", "count": 2, "label": ["PCI"]}],
              "metadata": {"id": "msg-001", "threadId": "thr-001", "labelIds": ["MARKER-LABELID"],
                "sizeEstimate": 121863, "internalDate": 1789725004000,
                "headers": {"from": "MARKER-HEADER <marker@example.test>", "subject": "MARKER-SUBJECT"},
                "mailFolderType": "Inbox", "api_token": "MARKER-TOKEN"}}}
            """;

    @Test
    @DisplayName("Gmail overview: whitelisted fields, text-only metadata, and no marker in the serialised DTO")
    void gmailOverview() {
        SaasFileOverview overview = mapper.toFileOverview(parse(GMAIL_OVERVIEW));
        assertThat(overview.name()).isEqualTo("Quarterly Plan.pdf");
        assertThat(overview.fileExtension()).isEqualTo("pdf");
        assertThat(overview.fileTypeLabel()).isEqualTo("PDF");
        assertThat(overview.driveType()).isEqualTo("GMAIL");
        assertThat(overview.accountName()).isEqualTo("ops-mailbox");
        assertThat(overview.owner()).isEqualTo("robin@example.test");
        assertThat(overview.categoryLabel()).isEqualTo("Financial");
        assertThat(overview.documentClassLabel()).isEqualTo("Budget Plans");
        assertThat(overview.sizeBytes()).isEqualTo(121863L);
        assertThat(overview.structured()).isFalse();
        assertThat(overview.lastModifiedAt()).isEqualTo(1789725004L);
        assertThat(overview.lastModifiedBy()).isEqualTo("robin@example.test");
        assertThat(overview.sensitiveRecords()).isEqualTo(7);
        assertThat(overview.policyViolations()).isEqualTo(2L);
        assertThat(overview.sensitives()).containsExactly(
                new SaasFileOverview.Sensitive("EMAIL ADDRESS", "Personal", 5),
                new SaasFileOverview.Sensitive("BANK ACCOUNT", "Financial", 2));
        assertThat(overview.metadata()).containsExactly(
                new SaasFileOverview.MetadataEntry("id", "Id", "msg-001"),
                new SaasFileOverview.MetadataEntry("threadId", "Threadid", "thr-001"),
                new SaasFileOverview.MetadataEntry("mailFolderType", "Mailfoldertype", "Inbox"));
        assertThat(overview.sharing()).isEqualTo(new SaasFileOverview.Sharing(
                List.of("robin@example.test", "sam@example.test"), List.of(), List.of(), List.of(),
                true, false, false, false));

        assertThat(serialise(overview)).doesNotContain(MARKER).doesNotContain("1789725004000")
                .doesNotContain("\"is").doesNotContain("file_id").doesNotContain("mip")
                .doesNotContain("headers").doesNotContain("labelIds").doesNotContain("sizeEstimate")
                .doesNotContain("internalDate").doesNotContain("api_token");
    }

    @Test
    @DisplayName("Drive overview: id, mimeType and name kept; modifiedTime, owners, permissions and size dropped")
    void driveOverview() {
        SaasFileOverview overview = mapper.toFileOverview(parse("""
                {"success": true, "overview": {"name": "Plan Draft", "file_extension": "docx",
                  "source": {"drive_type": "GOOGLE_WORKSPACE", "account_name": "drive-team"},
                  "is_structured": "yes",
                  "metadata": {"id": "drv-001", "mimeType": "application/vnd.synthetic.document",
                    "modifiedTime": {"value": 1789725004000, "dateOnly": false, "timeZoneShift": 0},
                    "name": "Plan Draft",
                    "owners": [{"displayName": "MARKER-OWNER", "emailAddress": "marker-owner@example.test",
                                "photoLink": "https://MARKER-PHOTO.example.test/p"}],
                    "permissions": [{"emailAddress": "marker-perm@example.test", "id": "MARKER-PERM"}],
                    "size": 4096, "longest": "%s", "tooLong": "%s", "blank": "  ", "nothing": null}}}
                """.formatted("x".repeat(2048), "MARKER" + "x".repeat(2043))));
        assertThat(overview.driveType()).isEqualTo("GOOGLE_WORKSPACE");
        assertThat(overview.fileTypeLabel()).isEqualTo("DOCX");
        assertThat(overview.structured()).isNull();
        assertThat(overview.sensitiveRecords()).isZero();
        assertThat(overview.policyViolations()).isNull();
        assertThat(overview.metadata()).extracting(SaasFileOverview.MetadataEntry::key)
                .containsExactly("id", "mimeType", "name", "longest");
        assertThat(overview.metadata()).extracting(SaasFileOverview.MetadataEntry::label)
                .containsExactly("Id", "Mimetype", "Name", "Longest");
        assertThat(serialise(overview)).doesNotContain(MARKER).doesNotContain("marker-")
                .doesNotContain("4096").doesNotContain("photoLink");
    }

    @Test
    @DisplayName("sharing: each list capped at 200 and over-long entries dropped; flags only for boolean true")
    void sharingCaps() {
        ObjectNode body = json.createObjectNode();
        ObjectNode sharing = body.putObject("overview").putObject("sharing");
        ArrayNode internal = sharing.putArray("internal_emails");
        for (int i = 0; i < 205; i++) {
            internal.add("user" + i + "@example.test");
        }
        sharing.putArray("external_emails").add("a".repeat(310) + "@example.test").add("guest@example.test");
        sharing.put("is_public", true).put("is_org_wide", "true").put("is_external_share", 1);

        SaasFileOverview.Sharing mapped = mapper.toFileOverview(body).sharing();
        assertThat(mapped.internalEmails()).hasSize(200).startsWith("user0@example.test").endsWith("user199@example.test");
        assertThat(mapped.externalEmails()).containsExactly("guest@example.test");
        assertThat(mapped.internalGroups()).isEmpty();
        assertThat(mapped.publicShare()).isTrue();
        assertThat(mapped.privateShare()).isFalse();
        assertThat(mapped.orgWide()).isFalse();
        assertThat(mapped.externalShare()).isFalse();
    }

    // ── SaaS file drawer: activity ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("activity: field fallbacks, ISO and millisecond times, newest first, untimed last, ties in API order")
    void activity() {
        SaasFileActivity activity = mapper.toActivity(parse("""
                {"success": true, "response": [
                  {"title": "Label applied", "status": "SUCCESS", "initiated_by": "robin@example.test",
                   "created_at": "2026-09-10T09:15:00Z"},
                  {"action_type": "REMOVE_PUBLIC_LINK", "status": "failed", "actor": "sam@example.test",
                   "timestamp": 1789725004377},
                  {"action": "Notify owner", "user": "lee@example.test", "time": 1789725100},
                  {"status": "PENDING", "initiator": "nobody@example.test"},
                  {"title": "Untimed", "created_by": "kim@example.test"},
                  "not-an-object",
                  {"action_title": "Second at the same time", "initiator": "ann@example.test", "executed_at": 1789725100},
                  {"title": "  ", "updated_at": "2026-09-01T00:00:00+02:00"}]}
                """));
        assertThat(activity.entries()).containsExactly(
                new SaasFileActivity.Entry("Notify owner", null, "lee@example.test", 1789725100L),
                new SaasFileActivity.Entry("Second at the same time", null, "ann@example.test", 1789725100L),
                new SaasFileActivity.Entry("Remove Public Link", "Failed", "sam@example.test", 1789725004L),
                new SaasFileActivity.Entry("Label applied", "Success", "robin@example.test",
                        epoch("2026-09-10T09:15:00Z")),
                new SaasFileActivity.Entry(null, null, null, epoch("2026-08-31T22:00:00Z")),
                new SaasFileActivity.Entry("Untimed", null, "kim@example.test", null));
        assertThat(mapper.toActivity(parse("{\"success\": true, \"response\": []}")).entries()).isEmpty();
        assertThat(mapper.toActivity(parse("{\"response\": {\"status\": \"OBJECT\"}}")).entries()).isEmpty();
    }

    // ── SaaS file drawer: lineage and copies ─────────────────────────────────────────────────────

    /**
     * Shaped like the re-captured Drive DOCX and Gmail PDF answers: four copies with distinct data object
     * ids, the Gmail PDF (legacy id ending f8) and three Drive files (ending f7). The Gmail copy's file id
     * is upper case; the DOCX carries {@code is_origin: true}.
     */
    private static final String COPIES = """
            {"success": true, "data": {
              "data_hash": "MARKER-HASH",
              "origin": {"data_object_id": "do-2", "file_id": "%s", "source": "SAAS", "name": "MARKER-ORIGIN",
                         "path": null, "size_bytes": 10, "deleted_at": null},
              "hash_coverage_note": "Copies are matched by content hash; renamed files still match",
              "known_copies": 4,
              "copies": [
                {"data_object_id": "do-1", "file_id": "%s", "source": "SAAS", "name": "Plan Draft.pdf",
                 "path": null, "extension": "PDF", "external_id": "MARKER-EXTERNAL",
                 "web_url": "https://MARKER-WEB.example.test/1", "public_url": "https://MARKER-PUBLIC.example.test/1",
                 "drive_id": null, "created": null, "is_public": false, "exposure": "MARKER-EXPOSURE",
                 "findings": [{"entity_type": "MARKER-FINDING", "count": 1}], "owner": null,
                 "last_modified_by": "robin@example.test", "current_os_user": null,
                 "created_at": "2026-09-10T09:15:00+00:00", "updated_at": "2026-09-12T00:00:00+00:00",
                 "is_origin": false, "event_count": 3,
                 "lane": {"resource_id": "res-1", "resource_type": "SAAS_PRINCIPAL", "kind": "saas_user",
                          "provider": "google_workspace", "display_name": "Robin Vale",
                          "external_id": "robin@example.test", "legacy_id": "aaaaaaaaaaaaaaaaaaaaaaf8",
                          "integration_id": "MARKER-INTEGRATION", "drive_type": "gmail", "hostname": null,
                          "os": null, "severity": "MARKER-SEVERITY"}},
                {"data_object_id": "do-2", "file_id": "%s", "source": "SAAS", "name": "Plan Draft.docx",
                 "extension": "docx", "owner": null, "last_modified_by": "sam@example.test",
                 "created_at": "2026-09-10T09:40:30Z", "is_origin": true,
                 "lane": {"resource_id": "res-2", "provider": "GOOGLE_WORKSPACE", "display_name": "Sam Reed",
                          "external_id": "sam@example.test", "legacy_id": "bbbbbbbbbbbbbbbbbbbbbbf7",
                          "drive_type": "GOOGLE_WORKSPACE"}},
                {"data_object_id": "do-3", "file_id": "%s", "source": "SAAS", "name": "Plan Draft (1).DOCX",
                 "owner": "sam@example.test", "created": "2026-09-11T08:00:00Z",
                 "lane": {"resource_id": "res-2", "provider": "GOOGLE_WORKSPACE", "display_name": null,
                          "external_id": "sam@example.test", "legacy_id": "bbbbbbbbbbbbbbbbbbbbbbf7",
                          "drive_type": "GOOGLE_WORKSPACE"}},
                {"data_object_id": "do-4", "file_id": "%s", "source": "SAAS", "name": "Plan Draft",
                 "owner": null, "last_modified_by": "sam@example.test",
                 "lane": {"resource_id": "res-2", "provider": "GOOGLE_WORKSPACE",
                          "external_id": "MARKER-NOT-AN-EMAIL", "legacy_id": "bbbbbbbbbbbbbbbbbbbbbbf7",
                          "drive_type": "GOOGLE_WORKSPACE"}}],
              "truncated": false, "too_common": false, "unhashed": false, "empty_file": false,
              "summary": {"locations": 3, "total_events": 0},
              "filters": {"providers": [{"value": "MARKER-FILTER", "count": 1}]}}}
            """.formatted(DRIVE_DOCX, GMAIL_PDF.toUpperCase(), DRIVE_DOCX, DRIVE_COPY, DRIVE_PDF);

    /** Four lanes matching the copies one to one and no events, as in every capture. */
    private static final String TIMELINE = """
            {"success": true, "data": {"data_hash": "MARKER-HASH", "lanes": [
               {"resource_id": "res-1", "data_object_id": "do-1", "legacy_id": "MARKER-LEGACY", "source": "SAAS"},
               {"resource_id": "res-2", "data_object_id": "do-2", "legacy_id": "MARKER-LEGACY", "source": "SAAS"},
               {"resource_id": "res-2", "data_object_id": "do-3", "legacy_id": "MARKER-LEGACY", "source": "SAAS"},
               {"resource_id": "res-2", "data_object_id": "do-4", "legacy_id": "MARKER-LEGACY", "source": "SAAS"}],
             "events": [], "next_cursor": "MARKER-CURSOR", "truncated": false, "unhashed": false}}
            """;

    @Test
    @DisplayName("lineage: one lane per copy in API order with opaque keys, First Seen times and owner fallbacks")
    void lineageLanes() {
        SaasFileLineage lineage = mapper.toLineage(parse(COPIES), parse(TIMELINE), DRIVE_DOCX);
        assertThat(lineage.available()).isTrue();
        assertThat(lineage.coverageNote()).isEqualTo("Copies are matched by content hash; renamed files still match");
        assertThat(lineage.events()).isEmpty();
        assertThat(lineage.lanes()).containsExactly(
                new SaasFileLineage.Lane("copy-0", "Plan Draft.pdf", "Robin Vale", "robin@example.test",
                        "GMAIL", null, "GOOGLE_WORKSPACE", "Gmail", false, epoch("2026-09-10T09:15:00Z")),
                new SaasFileLineage.Lane("copy-1", "Plan Draft.docx", "Sam Reed", "sam@example.test",
                        "GOOGLE_WORKSPACE", null, "GOOGLE_WORKSPACE", "Google Drive", true,
                        epoch("2026-09-10T09:40:30Z")),
                new SaasFileLineage.Lane("copy-2", "Plan Draft (1).DOCX", "sam@example.test", "sam@example.test",
                        "GOOGLE_WORKSPACE", null, "GOOGLE_WORKSPACE", "Google Drive", false,
                        epoch("2026-09-11T08:00:00Z")),
                new SaasFileLineage.Lane("copy-3", "Plan Draft", "sam@example.test", "sam@example.test",
                        "GOOGLE_WORKSPACE", null, "GOOGLE_WORKSPACE", "Google Drive", false, null));

        String serialised = serialise(lineage);
        assertThat(serialised).doesNotContain(MARKER).doesNotContain("https://")
                .doesNotContain("is_origin").doesNotContain("isOrigin").doesNotContain("origin")
                .doesNotContain("exposure").doesNotContain("known").doesNotContain("truncated")
                .doesNotContain("summary").doesNotContain("filters").doesNotContain("data_object_id")
                .doesNotContain("do-").doesNotContain("res-").doesNotContain("legacy").doesNotContain("bbbbbbbb")
                .doesNotContain(DRIVE_DOCX).doesNotContain(GMAIL_PDF.toUpperCase());
    }

    @Test
    @DisplayName("self: by file id ignoring case, never by is_origin; without a file id, only through data.origin")
    void selfRule() {
        List<Boolean> gmailSelf = mapper.toLineage(parse(COPIES), null, GMAIL_PDF).lanes().stream()
                .map(SaasFileLineage.Lane::self).toList();
        assertThat(gmailSelf).containsExactly(true, false, false, false);

        String noFileId = """
                {"data": {"origin": {"data_object_id": "do-1", "file_id": "%s"},
                  "copies": [{"data_object_id": "do-1", "name": "a.pdf"}, {"data_object_id": "do-2", "name": "b.pdf"},
                             {"name": "c.pdf"}]}}
                """;
        assertThat(mapper.toLineage(parse(noFileId.formatted(GMAIL_PDF)), null, GMAIL_PDF).lanes())
                .extracting(SaasFileLineage.Lane::self).containsExactly(true, false, false);
        assertThat(mapper.toLineage(parse(noFileId.formatted(DRIVE_PDF)), null, GMAIL_PDF).lanes())
                .extracting(SaasFileLineage.Lane::self).containsExactly(false, false, false);
    }

    @Test
    @DisplayName("lineage: timeline-only lanes are appended with tl keys; events match by object id, file id, resource")
    void timelineLanesAndEvents() {
        String timeline = """
                {"data": {"lanes": [
                   {"resource_id": "res-1", "data_object_id": "do-1"},
                   {"resource_id": "res-9", "data_object_id": "do-9", "display_name": null,
                    "hostname": "Build Server", "external_id": "SERIAL-NO-AT", "provider": "endpoint_agent",
                    "os": "linux", "source": "ENDPOINT_AGENT"},
                   "not-a-lane"],
                 "events": [
                   {"data_object_id": "do-3", "type": "FILE_SHARED", "actor": "robin@example.test",
                    "timestamp": "2026-09-12T10:00:00Z", "details": "MARKER-DETAILS"},
                   {"file_id": "%s", "event_type": "download", "user_email": "sam@example.test", "time": 1789725004377},
                   {"resource_id": "res-9", "action": "copied", "performed_by": "build@example.test",
                    "occurred_at": 1789725100},
                   {"resource_id": "res-2", "name": "renamed", "event_time": "2026-09-13T00:00:00Z"},
                   {"data_object_id": "do-unknown", "file_id": "eeeeeeeeeeeeeeeeeeeeeeee"}]}}
                """.formatted(DRIVE_PDF.toUpperCase());
        SaasFileLineage lineage = mapper.toLineage(parse(COPIES), parse(timeline), DRIVE_DOCX);

        assertThat(lineage.lanes()).extracting(SaasFileLineage.Lane::key)
                .containsExactly("copy-0", "copy-1", "copy-2", "copy-3", "tl-1");
        assertThat(lineage.lanes().get(4)).isEqualTo(new SaasFileLineage.Lane("tl-1", null, "Build Server", null,
                null, "LINUX", "ENDPOINT_AGENT", "Endpoint", false, null));
        assertThat(lineage.events()).containsExactly(
                new SaasFileLineage.Event("copy-2", "File Shared", "robin@example.test", epoch("2026-09-12T10:00:00Z")),
                new SaasFileLineage.Event("copy-3", "Download", "sam@example.test", 1789725004L),
                new SaasFileLineage.Event("tl-1", "Copied", "build@example.test", 1789725100L),
                new SaasFileLineage.Event("copy-1", "Renamed", null, epoch("2026-09-13T00:00:00Z")),
                new SaasFileLineage.Event(null, null, null, null));
        assertThat(serialise(lineage)).doesNotContain(MARKER).doesNotContain("SERIAL").doesNotContain("res-");
    }

    @Test
    @DisplayName("lineage: no timeline, an empty copies answer and an over-long coverage note")
    void lineageEdges() {
        SaasFileLineage withoutTimeline = mapper.toLineage(parse(COPIES), null, DRIVE_DOCX);
        assertThat(withoutTimeline.lanes()).hasSize(4);
        assertThat(withoutTimeline.events()).isEmpty();

        SaasFileLineage empty = mapper.toLineage(parse("{\"success\": true, \"data\": {\"copies\": []}}"), null,
                DRIVE_DOCX);
        assertThat(empty).isEqualTo(new SaasFileLineage(true, null, List.of(), List.of()));
        assertThat(mapper.toLineage(parse("{}"), parse("{}"), DRIVE_DOCX).lanes()).isEmpty();

        String longNote = "{\"data\": {\"hash_coverage_note\": \"" + "n".repeat(301) + "\"}}";
        assertThat(mapper.toLineage(parse(longNote), null, DRIVE_DOCX).coverageNote()).isNull();
        String note300 = "{\"data\": {\"hash_coverage_note\": \"" + "n".repeat(300) + "\"}}";
        assertThat(mapper.toLineage(parse(note300), null, DRIVE_DOCX).coverageNote()).hasSize(300);

        assertThat(SaasFileLineage.unavailable()).isEqualTo(new SaasFileLineage(false, null, List.of(), List.of()));
    }

    @Test
    @DisplayName("copies: the self copy is left out, locations counts distinct lanes, labels per source")
    void copies() {
        SaasFileCopies copies = mapper.toCopies(parse(COPIES), DRIVE_DOCX);
        assertThat(copies.available()).isTrue();
        assertThat(copies.copies()).extracting(SaasFileCopies.Copy::key).containsExactly("copy-0", "copy-2", "copy-3");
        assertThat(copies.locations()).isEqualTo(3);
        assertThat(copies.copies().get(0)).isEqualTo(new SaasFileCopies.Copy("copy-0", "Plan Draft.pdf", "pdf", null,
                "GMAIL", null, "GOOGLE_WORKSPACE", "Gmail", "SaaS \u00B7 Gmail", "Robin Vale", "robin@example.test",
                "robin@example.test"));
        assertThat(copies.copies().get(1).extension()).isEqualTo("docx");
        assertThat(copies.copies().get(1).locationLabel()).isEqualTo("SaaS \u00B7 Google Drive");
        assertThat(copies.copies().get(2).extension()).isNull();
        assertThat(copies.copies()).extracting(SaasFileCopies.Copy::userOrDevice)
                .containsExactly("robin@example.test", "sam@example.test", "sam@example.test");

        SaasFileCopies fromGmail = mapper.toCopies(parse(COPIES), GMAIL_PDF);
        assertThat(fromGmail.copies()).extracting(SaasFileCopies.Copy::key).containsExactly("copy-1", "copy-2", "copy-3");
        assertThat(fromGmail.locations()).isEqualTo(3);

        assertThat(serialise(copies)).doesNotContain(MARKER).doesNotContain("https://").doesNotContain("origin")
                .doesNotContain("do-").doesNotContain("res-").doesNotContain("bbbbbbbb").doesNotContain("summary");
        assertThat(SaasFileCopies.unavailable()).isEqualTo(new SaasFileCopies(false, List.of(), 0));
    }

    @Test
    @DisplayName("copies without a usable summary: locations counts the distinct lanes of every copy, self included")
    void copiesWithoutSummary() {
        String noSummary = COPIES.replace("\"summary\": {\"locations\": 3, \"total_events\": 0},", "");
        assertThat(noSummary).doesNotContain("summary");
        assertThat(mapper.toCopies(parse(noSummary), DRIVE_DOCX).locations()).isEqualTo(2);
        assertThat(mapper.toCopies(parse(noSummary), GMAIL_PDF).locations()).isEqualTo(2);

        String negative = COPIES.replace("\"locations\": 3", "\"locations\": -1");
        assertThat(mapper.toCopies(parse(negative), DRIVE_DOCX).locations()).isEqualTo(2);
        String text = COPIES.replace("\"locations\": 3", "\"locations\": \"3\"");
        assertThat(mapper.toCopies(parse(text), DRIVE_DOCX).locations()).isEqualTo(2);
        String zero = COPIES.replace("\"locations\": 3", "\"locations\": 0");
        assertThat(mapper.toCopies(parse(zero), DRIVE_DOCX).locations()).isZero();
    }

    @Test
    @DisplayName("file overview Category: each word's first letter upper-cased, the rest kept")
    void overviewCategoryChipLabel() {
        for (String[] pair : new String[][]{{"other", "Other"}, {"it & technical", "It & Technical"},
                {"IT & Technical", "IT & Technical"}, {"Field Notes", "Field Notes"},
                {"QA and Release", "QA And Release"}}) {
            String body = "{\"overview\": {\"name\": \"a.pdf\", \"category_label\": \"" + pair[0] + "\"}}";
            assertThat(mapper.toFileOverview(parse(body)).categoryLabel()).isEqualTo(pair[1]);
        }
        assertThat(mapper.toFileOverview(parse("{\"overview\": {\"name\": \"a.pdf\"}}")).categoryLabel()).isNull();
    }

    @Test
    @DisplayName("an endpoint copy: path, Endpoint labels and the OS user as User/Device Name")
    void endpointCopy() {
        SaasFileCopies copies = mapper.toCopies(parse("""
                {"data": {"copies": [
                  {"data_object_id": "do-7", "file_id": "0000aaaa-0000-4000-8000-00000000000c",
                   "source": "ENDPOINT_AGENT", "name": "plan.docx", "path": "/Users/synthetic/notes/plan.docx",
                   "external_id": "MARKER-EXTERNAL", "owner": null, "last_modified_by": null,
                   "current_os_user": "rvale", "created_at": "2026-09-10T11:00:00+00:00",
                   "lane": {"resource_id": "res-7", "provider": "ENDPOINT_AGENT", "display_name": "Robin Laptop",
                            "external_id": "MARKER-SERIAL", "legacy_id": "cccccccccccccccccccccccc",
                            "drive_type": null, "hostname": "Robin Laptop", "os": "MAC"}},
                  {"source": "ENDPOINT_AGENT", "name": "plan-copy.docx", "current_os_user": null,
                   "lane": {"hostname": "Build Box", "os": "WINDOWS"}}]}}
                """), GMAIL_PDF);
        assertThat(copies.copies()).containsExactly(
                new SaasFileCopies.Copy("copy-0", "plan.docx", "docx", "/Users/synthetic/notes/plan.docx", null, "MAC",
                        "ENDPOINT_AGENT", "Endpoint", "Endpoint \u00B7 macOS", "Robin Laptop", null, "rvale"),
                new SaasFileCopies.Copy("copy-1", "plan-copy.docx", "docx", null, null, "WINDOWS", null, "Endpoint",
                        "Endpoint \u00B7 Windows", "Build Box", null, "Build Box"));
        assertThat(copies.locations()).isEqualTo(2);
        assertThat(serialise(copies)).doesNotContain(MARKER).doesNotContain("cccccccc");
    }
}
