package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.7: the label maps, their fallbacks and the SaaS provider grouping. */
class PageLabelsTest {

    @Test
    @DisplayName("known codes map to the console's wording")
    void knownCodes() {
        assertThat(PageLabels.platform("on_prem")).isEqualTo("On-Prem");
        assertThat(PageLabels.platform("cloud")).isEqualTo("Cloud");
        assertThat(PageLabels.coverage("SELECTED_DATASTORES")).isEqualTo("Selected datastores");
        assertThat(PageLabels.trigger("IMMEDIATE")).isEqualTo("Immediate");
        assertThat(PageLabels.runStatus("IN_PROGRESS")).isEqualTo("Progress");
        assertThat(PageLabels.runStatus("NOT_STARTED")).isEqualTo("Not started");
        assertThat(PageLabels.runPhase("FILE_DISCOVERY")).isEqualTo("File Discovery");
        assertThat(PageLabels.remediationStatus("WAITING_APPROVAL")).isEqualTo("Awaiting Approval");
        assertThat(PageLabels.remediationStatus("NOOP")).isEqualTo("No Change");
        assertThat(PageLabels.remediationAction("REMOVE_EXTERNAL_ACCESS_BY_TIME"))
                .isEqualTo("Remove External Access (timed)");
        assertThat(PageLabels.remediationAction("MOVE_TO_QUARANTINE")).isEqualTo("Move to Quarantine");
        assertThat(PageLabels.remediationTrigger("AI_AGENT")).isEqualTo("AI Agent");
        assertThat(PageLabels.saasUserStatus("SUSPENDED")).isEqualTo("Suspended");
    }

    @Test
    @DisplayName("unknown codes are prettified; null and blank stay null")
    void fallbacks() {
        assertThat(PageLabels.runStatus("SOME_NEW_CODE")).isEqualTo("Some New Code");
        assertThat(PageLabels.remediationAction("ARCHIVE_FILE")).isEqualTo("Archive File");
        assertThat(PageLabels.platform(null)).isNull();
        assertThat(PageLabels.coverage(" ")).isNull();
        assertThat(PageLabels.remediationStatus(null)).isNull();
    }

    @Test
    @DisplayName("assist status: ACTIVE is Active, any other code Disabled, null null")
    void assistStatus() {
        assertThat(PageLabels.assistStatus("ACTIVE")).isEqualTo("Active");
        assertThat(PageLabels.assistStatus("INACTIVE")).isEqualTo("Disabled");
        assertThat(PageLabels.assistStatus("UNHEALTHY")).isEqualTo("Disabled");
        assertThat(PageLabels.assistStatus(null)).isNull();
    }

    @Test
    @DisplayName("SaaS subtypes group into console providers; unknown subtypes give null")
    void saasProvider() {
        assertThat(PageLabels.saasProvider("GMAIL")).isEqualTo("GOOGLE_WORKSPACE");
        assertThat(PageLabels.saasProvider("GOOGLE_WORKSPACE")).isEqualTo("GOOGLE_WORKSPACE");
        assertThat(PageLabels.saasProvider("GOOGLE_DRIVE")).isEqualTo("GOOGLE_WORKSPACE");
        assertThat(PageLabels.saasProvider("SLACK")).isEqualTo("SLACK");
        assertThat(PageLabels.saasProvider("SHAREPOINT")).isEqualTo("MICROSOFT_365");
        assertThat(PageLabels.saasProvider("ONE_DRIVE")).isEqualTo("MICROSOFT_365");
        assertThat(PageLabels.saasProvider("JIRA")).isEqualTo("JIRA");
        assertThat(PageLabels.saasProvider("DROPBOX")).isNull();
        assertThat(PageLabels.saasProvider(null)).isNull();
    }
}
