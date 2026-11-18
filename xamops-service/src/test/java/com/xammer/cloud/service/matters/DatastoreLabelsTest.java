package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.7: the console wording added for the SaaS, SaaS file and Endpoint drawers. */
class DatastoreLabelsTest {

    @Test
    @DisplayName("SaaS scan states, with unlisted codes prettified and null kept null")
    void saasScanStates() {
        assertThat(DatastoreLabels.saasScanStateLabel("COMPLETED")).isEqualTo("Completed");
        assertThat(DatastoreLabels.saasScanStateLabel("DISABLED")).isEqualTo("Disabled");
        assertThat(DatastoreLabels.saasScanStateLabel("ENABLED")).isEqualTo("Enabled");
        assertThat(DatastoreLabels.saasScanStateLabel("in_progress")).isEqualTo("In Progress");
        assertThat(DatastoreLabels.saasScanStateLabel("FAILED")).isEqualTo("Failed");
        assertThat(DatastoreLabels.saasScanStateLabel("PARTIALLY_DONE")).isEqualTo("Partially Done");
        assertThat(DatastoreLabels.saasScanStateLabel(null)).isNull();
        assertThat(DatastoreLabels.saasScanStateLabel(" ")).isNull();
    }

    @Test
    @DisplayName("Scan Summary file types and the file drawer's File Type: seen ones mapped, others upper-cased")
    void fileTypes() {
        assertThat(DatastoreLabels.saasFileTypeLabel("msg")).isEqualTo("Email");
        assertThat(DatastoreLabels.saasFileTypeLabel("png")).isEqualTo("Images");
        assertThat(DatastoreLabels.saasFileTypeLabel("PDF")).isEqualTo("PDF");
        assertThat(DatastoreLabels.saasFileTypeLabel("docx")).isEqualTo("DOCX");
        assertThat(DatastoreLabels.saasFileTypeLabel("")).isNull();
        assertThat(DatastoreLabels.saasFileTypeLabel(null)).isNull();

        assertThat(DatastoreLabels.fileExtensionDescription("msg")).isEqualTo("Outlook Message");
        assertThat(DatastoreLabels.fileExtensionDescription("pdf")).isEqualTo("PDF");
        assertThat(DatastoreLabels.fileExtensionDescription("xlsx")).isEqualTo("XLSX");
        assertThat(DatastoreLabels.fileExtensionDescription("  ")).isNull();
        assertThat(DatastoreLabels.fileExtensionDescription(null)).isNull();
    }

    @Test
    @DisplayName("document categories: it, hr and kyc upper-cased, other words capitalised, & kept")
    void documentCategories() {
        assertThat(DatastoreLabels.documentCategoryLabel("it & technical")).isEqualTo("IT & Technical");
        assertThat(DatastoreLabels.documentCategoryLabel("identity & personal")).isEqualTo("Identity & Personal");
        assertThat(DatastoreLabels.documentCategoryLabel("HR  records")).isEqualTo("HR Records");
        assertThat(DatastoreLabels.documentCategoryLabel("kyc")).isEqualTo("KYC");
        assertThat(DatastoreLabels.documentCategoryLabel("FINANCIAL")).isEqualTo("Financial");
        assertThat(DatastoreLabels.documentCategoryLabel("")).isNull();
        assertThat(DatastoreLabels.documentCategoryLabel(null)).isNull();
    }

    @Test
    @DisplayName("endpoint agent status: ACTIVE and ONLINE are Online, INACTIVE and OFFLINE Offline (GAP-49)")
    void agentStatus() {
        assertThat(DatastoreLabels.endpointAgentStatusLabel("ACTIVE")).isEqualTo("Online");
        assertThat(DatastoreLabels.endpointAgentStatusLabel("online")).isEqualTo("Online");
        assertThat(DatastoreLabels.endpointAgentStatusLabel("INACTIVE")).isEqualTo("Offline");
        assertThat(DatastoreLabels.endpointAgentStatusLabel("OFFLINE")).isEqualTo("Offline");
        assertThat(DatastoreLabels.endpointAgentStatusLabel("UNINSTALLED")).isEqualTo("Uninstalled");
        assertThat(DatastoreLabels.endpointAgentStatusLabel(null)).isNull();

        assertThat(DatastoreLabels.endpointAgentOnline("ACTIVE")).isTrue();
        assertThat(DatastoreLabels.endpointAgentOnline("online")).isTrue();
        assertThat(DatastoreLabels.endpointAgentOnline("INACTIVE")).isFalse();
        assertThat(DatastoreLabels.endpointAgentOnline("UNINSTALLED")).isFalse();
        assertThat(DatastoreLabels.endpointAgentOnline(null)).isFalse();
    }

    @Test
    @DisplayName("endpoint scan statuses")
    void scanStatuses() {
        assertThat(DatastoreLabels.endpointScanStatusLabel("COMPLETED")).isEqualTo("Completed");
        assertThat(DatastoreLabels.endpointScanStatusLabel("IN_PROGRESS")).isEqualTo("In Progress");
        assertThat(DatastoreLabels.endpointScanStatusLabel("TIMED_OUT")).isEqualTo("Timed Out");
        assertThat(DatastoreLabels.endpointScanStatusLabel("FAILED")).isEqualTo("Failed");
        assertThat(DatastoreLabels.endpointScanStatusLabel("QUEUED")).isEqualTo("Queued");
        assertThat(DatastoreLabels.endpointScanStatusLabel(null)).isNull();
    }

    @Test
    @DisplayName("extension chips: the known set upper-cased, any other capitalised (A15)")
    void extensions() {
        assertThat(DatastoreLabels.extensionLabel("md")).isEqualTo("MD");
        assertThat(DatastoreLabels.extensionLabel("YML")).isEqualTo("YML");
        assertThat(DatastoreLabels.extensionLabel("docx")).isEqualTo("DOCX");
        assertThat(DatastoreLabels.extensionLabel("mp3")).isEqualTo("MP3");
        assertThat(DatastoreLabels.extensionLabel("csv")).isEqualTo("CSV");
        assertThat(DatastoreLabels.extensionLabel("bak3")).isEqualTo("Bak3");
        assertThat(DatastoreLabels.extensionLabel("ps1")).isEqualTo("Ps1");
        assertThat(DatastoreLabels.extensionLabel("properties")).isEqualTo("Properties");
        assertThat(DatastoreLabels.extensionLabel("TSBUILDINFO")).isEqualTo("Tsbuildinfo");
        assertThat(DatastoreLabels.extensionLabel("shell")).isEqualTo("Shell");
        assertThat(DatastoreLabels.extensionLabel("sh")).isEqualTo("SH");
        assertThat(DatastoreLabels.extensionLabel(null)).isNull();
        assertThat(DatastoreLabels.extensionLabel(" ")).isNull();
        assertThat(DatastoreLabels.KNOWN_UPPER_EXTENSIONS).hasSize(22);
    }

    @Test
    @DisplayName("metadata keys: first character upper-cased, the rest lower-cased")
    void metadataKeys() {
        assertThat(DatastoreLabels.metadataLabel("threadId")).isEqualTo("Threadid");
        assertThat(DatastoreLabels.metadataLabel("mailFolderType")).isEqualTo("Mailfoldertype");
        assertThat(DatastoreLabels.metadataLabel("id")).isEqualTo("Id");
        assertThat(DatastoreLabels.metadataLabel("mimeType")).isEqualTo("Mimetype");
        assertThat(DatastoreLabels.metadataLabel("")).isNull();
        assertThat(DatastoreLabels.metadataLabel(null)).isNull();
    }

    @Test
    @DisplayName("titleCase: the Endpoint File View's spelling (GAP-51)")
    void titleCase() {
        assertThat(DatastoreLabels.titleCase("it & technical")).isEqualTo("It & Technical");
        assertThat(DatastoreLabels.titleCase("system documentation")).isEqualTo("System Documentation");
        assertThat(DatastoreLabels.titleCase(" INCIDENT   reports ")).isEqualTo("Incident Reports");
        assertThat(DatastoreLabels.titleCase("")).isNull();
        assertThat(DatastoreLabels.titleCase(null)).isNull();
    }

    @Test
    @DisplayName("copy source: the drive type's label, else Endpoint, else the source prettified")
    void copySource() {
        assertThat(DatastoreLabels.copySourceLabel("SAAS", "GMAIL", null)).isEqualTo("Gmail");
        assertThat(DatastoreLabels.copySourceLabel("SAAS", "GOOGLE_WORKSPACE", null)).isEqualTo("Google Drive");
        assertThat(DatastoreLabels.copySourceLabel("SAAS", "GOOGLE_DRIVE", null)).isEqualTo("Google Drive");
        assertThat(DatastoreLabels.copySourceLabel("ENDPOINT", null, null)).isEqualTo("Endpoint");
        assertThat(DatastoreLabels.copySourceLabel("ENDPOINT_AGENT", null, null)).isEqualTo("Endpoint");
        assertThat(DatastoreLabels.copySourceLabel(null, null, "MAC")).isEqualTo("Endpoint");
        assertThat(DatastoreLabels.copySourceLabel("CLOUD_STORAGE", null, null)).isEqualTo("Cloud Storage");
        assertThat(DatastoreLabels.copySourceLabel(null, null, null)).isNull();
    }

    @Test
    @DisplayName("copy location: SaaS · Gmail, SaaS · Google Drive, Endpoint · macOS, or the source alone")
    void copyLocation() {
        assertThat(DatastoreLabels.copyLocationLabel("SAAS", "GMAIL", null)).isEqualTo("SaaS \u00B7 Gmail");
        assertThat(DatastoreLabels.copyLocationLabel("saas", "GOOGLE_WORKSPACE", null))
                .isEqualTo("SaaS \u00B7 Google Drive");
        assertThat(DatastoreLabels.copyLocationLabel("ENDPOINT", null, "MAC")).isEqualTo("Endpoint \u00B7 macOS");
        assertThat(DatastoreLabels.copyLocationLabel("ENDPOINT_AGENT", null, "MAC")).isEqualTo("Endpoint \u00B7 macOS");
        assertThat(DatastoreLabels.copyLocationLabel("ENDPOINT_AGENT", null, "WINDOWS"))
                .isEqualTo("Endpoint \u00B7 Windows");
        assertThat(DatastoreLabels.copyLocationLabel("ENDPOINT_AGENT", null, "LINUX")).isEqualTo("Endpoint \u00B7 Linux");
        assertThat(DatastoreLabels.copyLocationLabel("ENDPOINT_AGENT", null, "SOLARIS"))
                .isEqualTo("Endpoint \u00B7 Solaris");
        assertThat(DatastoreLabels.copyLocationLabel("SAAS", null, null)).isEqualTo("SaaS");
        assertThat(DatastoreLabels.copyLocationLabel("CLOUD_STORAGE", null, null)).isEqualTo("Cloud Storage");
        assertThat(DatastoreLabels.copyLocationLabel(null, "GMAIL", null)).isEqualTo("Gmail");
        assertThat(DatastoreLabels.copyLocationLabel(null, null, null)).isNull();
    }

    @Test
    @DisplayName("the build 1 maps are unchanged")
    void existingMapsUnchanged() {
        assertThat(DatastoreLabels.subtypeLabel("GMAIL")).isEqualTo("Gmail");
        assertThat(DatastoreLabels.subtypeLabel("GOOGLE_WORKSPACE")).isEqualTo("Google Drive");
        assertThat(DatastoreLabels.riskLevelLabel("HIGH")).isEqualTo("High");
        assertThat(DatastoreLabels.osLabel("MAC")).isEqualTo("macOS");
        assertThat(DatastoreLabels.agentStatusLabel("ONLINE")).isEqualTo("Online");
        assertThat(DatastoreLabels.deviceTypeLabel("laptop")).isEqualTo("Laptop");
    }
}
