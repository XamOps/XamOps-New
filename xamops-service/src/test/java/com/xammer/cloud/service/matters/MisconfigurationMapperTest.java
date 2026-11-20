package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Build 5, plan 1.3 and 1.4: findings per datastore, grouped per policy. Synthetic bodies only. */
class MisconfigurationMapperTest {

    private static final String MARKER = "SYNTHMARKER";
    private static final String DS_A = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DS_B = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DS_C = "cccccccccccccccccccccccc";

    private final ObjectMapper json = new ObjectMapper();
    private final MisconfigurationMapper mapper = new MisconfigurationMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DatastoreRow row(String sourceId, String category, String accountLabel, long records,
                                    List<String> labels) {
        return new DatastoreRow("id-" + sourceId, sourceId, "store-" + sourceId.charAt(0), category, "S3", "S3",
                new DatastoreRow.Risk(null, null, null), new DatastoreRow.Sensitives(records, labels),
                new DatastoreRow.Source("S3", "Amazon S3", accountLabel, "eu-west-1"),
                new DatastoreRow.Scan("CLASSIFIED", "Classified", 1700000000L), null);
    }

    private static MisconfigurationMapper.Issue issue(String policyId, String title, String severity,
                                                      String framework) {
        return new MisconfigurationMapper.Issue(policyId, title, "MATTERS", severity, "desc " + title,
                "fix " + title, null, null, framework);
    }

    // ── toIssues ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a finding maps its fields; source and severity are upper-cased; blank CLI is null")
    void issueFields() {
        List<MisconfigurationMapper.Issue> issues = mapper.toIssues(parse("""
                {"success": true, "risk": [
                  {"risk": "Bucket logging disabled", "policy_id": "p-0123456789abcdef", "source": "upwind",
                   "severity": "medium", "description": "## Why", "remediation": "1. Enable it",
                   "remediation_recommendation_code_cli": "  ", "learn_more": "https://docs.example.test/a",
                   "compliance": {"name": "  Synthetic Framework  ", "clause": "%1$s-clause"},
                   "details": {"x": "%1$s-details"}},
                  {"risk": "Second", "policy_id": "snake_case_rule", "source": "MATTERS", "severity": "High",
                   "remediation_recommendation_code_cli": "aws s3api put-bucket-logging",
                   "compliance": {"name": "   "}}
                ]}
                """.formatted(MARKER)));

        assertThat(issues).containsExactly(
                new MisconfigurationMapper.Issue("p-0123456789abcdef", "Bucket logging disabled", "UPWIND",
                        "MEDIUM", "## Why", "1. Enable it", null, "https://docs.example.test/a",
                        "Synthetic Framework"),
                new MisconfigurationMapper.Issue("snake_case_rule", "Second", "MATTERS", "HIGH", null, null,
                        "aws s3api put-bucket-logging", null, null));
    }

    @Test
    @DisplayName("learn_more is kept only for an absolute http or https URL")
    void learnMoreIsWebOnly() {
        List<MisconfigurationMapper.Issue> issues = mapper.toIssues(parse("""
                {"risk": [
                  {"policy_id": "p-1", "learn_more": "https://docs.example.test/x"},
                  {"policy_id": "p-2", "learn_more": "http://docs.example.test/y"},
                  {"policy_id": "p-3", "learn_more": "javascript:alert(1)"},
                  {"policy_id": "p-4", "learn_more": "/relative/path"},
                  {"policy_id": "p-5", "learn_more": "ftp://files.example.test/z"},
                  {"policy_id": "p-6", "learn_more": "not a url"},
                  {"policy_id": "p-7", "learn_more": null}
                ]}
                """));

        assertThat(issues).extracting(MisconfigurationMapper.Issue::learnMore).containsExactly(
                "https://docs.example.test/x", "http://docs.example.test/y", null, null, null, null, null);
    }

    @Test
    @DisplayName("a finding with a blank, missing or unusable policy id is skipped")
    void invalidPolicyIdsAreSkipped() {
        List<MisconfigurationMapper.Issue> issues = mapper.toIssues(parse("""
                {"risk": [
                  {"policy_id": "", "risk": "blank"},
                  {"risk": "missing"},
                  {"policy_id": "p/1", "risk": "slash"},
                  {"policy_id": "-p", "risk": "leading dash"},
                  {"policy_id": {"nested": true}, "risk": "object"},
                  {"policy_id": "a.b-c_d", "risk": "kept"}
                ]}
                """));

        assertThat(issues).extracting(MisconfigurationMapper.Issue::title).containsExactly("kept");
        assertThat(mapper.toIssues(parse("{\"success\": true}"))).isEmpty();
    }

    @Test
    @DisplayName("lastScannedAt reads scan_status.last_scanned_at only when it is a number")
    void lastScannedAt() {
        assertThat(MisconfigurationMapper.lastScannedAt(parse(
                "{\"scan_status\": {\"last_scanned_at\": 1700000123}}"))).isEqualTo(1700000123L);
        assertThat(MisconfigurationMapper.lastScannedAt(parse(
                "{\"scan_status\": {\"last_scanned_at\": \"1700000123\"}}"))).isNull();
        assertThat(MisconfigurationMapper.lastScannedAt(parse("{\"scan_status\": {}}"))).isNull();
        assertThat(MisconfigurationMapper.lastScannedAt(parse("{}"))).isNull();
    }

    // ── toSnapshot ───────────────────────────────────────────────────────────────────────────────

    private List<MisconfigurationMapper.Source> threeDatastores() {
        DatastoreRow a = row(DS_A, "cloud", "account-one", 10, List.of("Personal"));
        DatastoreRow b = row(DS_B, "cloud", "account-two", 5, List.of("IT & Security", "Personal"));
        DatastoreRow c = row(DS_C, "on_prem", "store-c", 7, List.of("Financial"));
        return List.of(
                new MisconfigurationMapper.Source(a, 1700000001L, List.of(
                        issue("p-medium", "Logging disabled", "MEDIUM", "NIST"),
                        issue("p-medium", "Logging disabled (second row)", "LOW", "GDPR"),
                        issue("p-high", "Public access", "HIGH", null))),
                new MisconfigurationMapper.Source(b, null, List.of(
                        issue("p-medium", "Other title", "HIGH", "NIST"),
                        issue("p-zeta", "zeta rule", "HIGH", "SOC2"),
                        issue("p-odd", "Odd severity", "INFO", null))),
                new MisconfigurationMapper.Source(c, 1700000003L, List.of(
                        issue("p-medium", "Logging disabled", "MEDIUM", "SOC2"),
                        issue("p-alpha", "Alpha rule", "HIGH", null),
                        issue("p-crit", "Critical rule", "CRITICAL", null))));
    }

    @Test
    @DisplayName("grouping: first-row fields, distinct frameworks first seen first, each datastore once, source order")
    void grouping() {
        MisconfigurationSnapshot snapshot = mapper.toSnapshot(threeDatastores(), 3, false);

        MisconfigurationDetail medium = snapshot.policies().stream()
                .filter(p -> p.policyId().equals("p-medium")).findFirst().orElseThrow();
        assertThat(medium.title()).isEqualTo("Logging disabled");
        assertThat(medium.severity()).isEqualTo("MEDIUM");
        assertThat(medium.severityLabel()).isEqualTo("Medium");
        assertThat(medium.description()).isEqualTo("desc Logging disabled");
        assertThat(medium.frameworks()).containsExactly("NIST", "GDPR", "SOC2");
        assertThat(medium.affected()).extracting(d -> d.datastore().sourceId()).containsExactly(DS_A, DS_B, DS_C);
        assertThat(medium.affected()).extracting(MisconfigurationDetail.AffectedDatastore::lastScannedAt)
                .containsExactly(1700000001L, null, 1700000003L);
        assertThat(snapshot.datastoresRead()).isEqualTo(3);
        assertThat(snapshot.capped()).isFalse();
    }

    @Test
    @DisplayName("order: CRITICAL, HIGH, MEDIUM, LOW, other; then affected descending; then title ignoring case; then id")
    void order() {
        DatastoreRow a = row(DS_A, "cloud", null, 0, List.of());
        DatastoreRow b = row(DS_B, "cloud", null, 0, List.of());
        List<MisconfigurationMapper.Source> sources = List.of(
                new MisconfigurationMapper.Source(a, null, List.of(
                        issue("p-low", "Low rule", "LOW", null),
                        issue("p-other", "Other rule", "INFO", null),
                        issue("p-h-b", "beta", "HIGH", null),
                        issue("p-h-twice", "zz many", "HIGH", null),
                        issue("p-h-2", "Same", "HIGH", null),
                        issue("p-h-1", "same", "HIGH", null),
                        issue("p-med", "Medium rule", "MEDIUM", null),
                        issue("p-crit", "Critical rule", "CRITICAL", null),
                        issue("p-h-a", "Alpha", "HIGH", null))),
                new MisconfigurationMapper.Source(b, null, List.of(issue("p-h-twice", "zz many", "HIGH", null))));

        assertThat(mapper.toSnapshot(sources, 2, true).policies()).extracting(MisconfigurationDetail::policyId)
                .containsExactly("p-crit", "p-h-twice", "p-h-a", "p-h-b", "p-h-1", "p-h-2", "p-med", "p-low",
                        "p-other");
    }

    // ── toList ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cards: distinct datastores; policies by severity, another severity in the total only")
    void cards() {
        MisconfigurationList list = mapper.toList(mapper.toSnapshot(threeDatastores(), 3, true));

        assertThat(list.totalAffectedResources()).isEqualTo(3);
        assertThat(list.open()).isEqualTo(new MisconfigurationList.SeverityCounts(6, 1, 3, 1, 0));
        assertThat(list.datastoresRead()).isEqualTo(3);
        assertThat(list.capped()).isTrue();
        assertThat(list.rows()).extracting(MisconfigurationList.Row::policyId)
                .containsExactly("p-crit", "p-alpha", "p-high", "p-zeta", "p-medium", "p-odd");
    }

    @Test
    @DisplayName("rows: affected count, sensitives summed, labels united, cloud accounts from cloud rows only")
    void rows() {
        MisconfigurationList list = mapper.toList(mapper.toSnapshot(threeDatastores(), 3, false));

        MisconfigurationList.Row medium = list.rows().stream()
                .filter(r -> r.policyId().equals("p-medium")).findFirst().orElseThrow();
        assertThat(medium).isEqualTo(new MisconfigurationList.Row("p-medium", "Logging disabled", "MATTERS",
                "MEDIUM", "Medium", 3, 22L, List.of("Personal", "IT & Security", "Financial"),
                List.of("NIST", "GDPR", "SOC2"), List.of("account-one", "account-two")));

        MisconfigurationList.Row odd = list.rows().get(list.rows().size() - 1);
        assertThat(odd.severity()).isEqualTo("INFO");
        assertThat(odd.frameworks()).isEmpty();
        assertThat(odd.cloudAccounts()).containsExactly("account-two");

        MisconfigurationList.Row onPremOnly = list.rows().stream()
                .filter(r -> r.policyId().equals("p-alpha")).findFirst().orElseThrow();
        assertThat(onPremOnly.cloudAccounts()).isEmpty();
        assertThat(onPremOnly.sensitiveRecords()).isEqualTo(7L);
    }

    @Test
    @DisplayName("an empty project has zero cards and no rows")
    void emptyProject() {
        MisconfigurationList list = mapper.toList(mapper.toSnapshot(List.of(), 0, false));

        assertThat(list.totalAffectedResources()).isZero();
        assertThat(list.open()).isEqualTo(new MisconfigurationList.SeverityCounts(0, 0, 0, 0, 0));
        assertThat(list.rows()).isEmpty();
    }

    @Test
    @DisplayName("details and compliance.clause never reach the snapshot or the list")
    void droppedFieldsNeverSerialize() throws Exception {
        List<MisconfigurationMapper.Issue> issues = mapper.toIssues(parse("""
                {"risk": [{"risk": "Rule", "policy_id": "p-1", "severity": "high",
                           "compliance": {"name": "Framework", "clause": "%1$s-clause"},
                           "details": {"resource": "%1$s-details", "list": ["%1$s-item"]}}]}
                """.formatted(MARKER)));
        MisconfigurationSnapshot snapshot = mapper.toSnapshot(List.of(new MisconfigurationMapper.Source(
                row(DS_A, "cloud", "account-one", 1, List.of()), null, issues)), 1, false);

        assertThat(json.writeValueAsString(snapshot)).doesNotContain(MARKER);
        assertThat(json.writeValueAsString(mapper.toList(snapshot))).doesNotContain(MARKER);
        assertThat(json.writeValueAsString(issues)).doesNotContain(MARKER);
    }
}
