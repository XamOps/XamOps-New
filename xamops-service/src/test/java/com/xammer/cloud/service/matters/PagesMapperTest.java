package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.pages.DocumentClassList;
import com.xammer.cloud.dto.matters.pages.RemediationActivity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.6.2 on synthetic bodies shaped like {@code /saas/categories} and {@code /remediation/activity}. */
class PagesMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final PagesMapper mapper = new PagesMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("document classes: one row per class in route order, duplicates in one category dropped")
    void documentClasses() {
        DocumentClassList list = mapper.toDocumentClasses(parse("{'success': true, 'categories': ["
                + "{'category': 'People Records', 'subcategories': ['Resumes', 'Offer Letters', 'Resumes']},"
                + "{'category': 'Finance Papers', 'subcategories': ['Invoices', 'Resumes', 7, null, '  ']},"
                + "{'category': 'People Records', 'subcategories': ['Payslips']},"
                + "{'category': null, 'subcategories': ['Orphans']},"
                + "{'category': 'Long', 'subcategories': ['" + "x".repeat(201) + "']}]}"));
        assertThat(list.categories()).containsExactly("People Records", "Finance Papers", "Long");
        assertThat(list.rows()).containsExactly(
                new DocumentClassList.Row("Resumes", "People Records"),
                new DocumentClassList.Row("Offer Letters", "People Records"),
                new DocumentClassList.Row("Invoices", "Finance Papers"),
                new DocumentClassList.Row("Resumes", "Finance Papers"),
                new DocumentClassList.Row("Payslips", "People Records"));
    }

    @Test
    @DisplayName("document classes: a body without categories is empty")
    void documentClassesEmpty() {
        DocumentClassList list = mapper.toDocumentClasses(parse("{'success': true}"));
        assertThat(list.categories()).isEmpty();
        assertThat(list.rows()).isEmpty();
    }

    @Test
    @DisplayName("remediation: summary, upper-snake status counts, total and the request's skip and limit")
    void remediationSummary() {
        RemediationActivity activity = mapper.toRemediation(parse("{'success': true, 'response': {"
                + "'summary': {'total': 7, 'waitingApproval': 2, 'inProgressScheduled': 1, 'completed': 3},"
                + "'statusCounts': {'ALL': 7, 'FAILED': 1, 'lower': 4, 'COMPLETED': 'x'},"
                + "'items': [], 'total': 7}}"), 10, 10);
        assertThat(activity.summary()).isEqualTo(new RemediationActivity.Summary(7, 2, 1, 3, 0));
        assertThat(activity.statusCounts()).containsExactly(entry("ALL", 7L), entry("FAILED", 1L));
        assertThat(activity.total()).isEqualTo(7);
        assertThat(activity.skip()).isEqualTo(10);
        assertThat(activity.limit()).isEqualTo(10);
        assertThat(activity.items()).isEmpty();
    }

    @Test
    @DisplayName("remediation: an item with every field, string and object approvers, approver merged first")
    void remediationItem() {
        RemediationActivity activity = mapper.toRemediation(parse("{'response': {'items': [{"
                + "'id': 'run-1', 'action_type': 'MAKE_PRIVATE', 'object_name': 'plan.docx',"
                + "'object_path': '/team/plan.docx', 'datastore_type': 'GOOGLE_WORKSPACE', 'source_type': 'SAAS',"
                + "'initiated_by': {'name': 'Ann Lee', 'email': 'ann@example.test', 'id': 'MARKER'},"
                + "'trigger_type': 'POLICY', 'created_at': 1700000000000, 'status': 'WAITING_APPROVAL',"
                + "'approvers': ['Bo Chan', {'name': 'Cy Diaz'}, {'email': 'dee@example.test'}],"
                + "'approver': {'name': 'Eve Fox'}, 'scheduled_at': '2026-01-02T03:04:05Z', 'secret': 'MARKER'},"
                + "{'id': 'run-2', 'initiated_by': 'system', 'approver': 'Bo Chan', 'approvers': ['Bo Chan']}]}}"),
                0, 10);
        RemediationActivity.Item item = activity.items().get(0);
        assertThat(item.id()).isEqualTo("run-1");
        assertThat(item.actionLabel()).isEqualTo("Make Private");
        assertThat(item.objectName()).isEqualTo("plan.docx");
        assertThat(item.objectPath()).isEqualTo("/team/plan.docx");
        assertThat(item.datastoreLabel()).isEqualTo("Google Drive");
        assertThat(item.sourceType()).isEqualTo("SAAS");
        assertThat(item.initiatedBy()).isEqualTo(new RemediationActivity.Person("Ann Lee", "ann@example.test"));
        assertThat(item.triggerLabel()).isEqualTo("Policy");
        assertThat(item.createdAt()).isEqualTo(1_700_000_000L);
        assertThat(item.statusLabel()).isEqualTo("Awaiting Approval");
        assertThat(item.approvers()).containsExactly("Eve Fox", "Bo Chan", "Cy Diaz", "dee@example.test");
        assertThat(item.scheduledAt()).isEqualTo(1_767_323_045L);

        RemediationActivity.Item second = activity.items().get(1);
        assertThat(second.initiatedBy()).isEqualTo(new RemediationActivity.Person("system", null));
        assertThat(second.approvers()).containsExactly("Bo Chan");
        assertThat(second.actionType()).isNull();
        assertThat(second.statusLabel()).isNull();
    }

    @Test
    @DisplayName("remediation: a body without response gives zeros and no items")
    void remediationEmpty() {
        RemediationActivity activity = mapper.toRemediation(parse("{'success': true}"), 0, 10);
        assertThat(activity.summary()).isEqualTo(new RemediationActivity.Summary(0, 0, 0, 0, 0));
        assertThat(activity.statusCounts()).isEmpty();
        assertThat(activity.total()).isZero();
        assertThat(activity.items()).isEmpty();
    }
}
