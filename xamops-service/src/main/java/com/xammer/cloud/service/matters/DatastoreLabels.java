package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Console wording for the XamLens Datastores page and drawer (design 5.7).
 *
 * <p>Separate from {@link DspmLabels}, which keeps the older wording for the old endpoints (its scan
 * status reads "Scanned" where the console's Datastores page reads "Classified"). Shared rules are
 * delegated: unlisted codes fall back to {@link DspmLabels#prettify} and sensitive categories use
 * {@link DspmLabels#sensitiveCategoryLabel}. Null or blank input gives null unless a method says
 * otherwise.
 */
public final class DatastoreLabels {

    private DatastoreLabels() {
    }

    private static final Map<String, String> PROVIDERS = Map.of(
            "EBS", "EBS",
            "S3", "S3",
            "AZURE_BLOB", "Azure Blob",
            "BIGQUERY", "BigQuery",
            "DYNAMODB", "DynamoDB",
            "GCS", "GCS",
            "RDS", "RDS",
            "GOOGLE_WORKSPACE", "Google Workspace",
            "ENDPOINT_AGENT", "Endpoint Agent",
            "SELF_HOSTED_POSTGRES", "PostgreSQL (self-hosted)");

    private static final Map<String, String> SUBTYPES = Map.of(
            "S3", "S3",
            "GMAIL", "Gmail",
            "GOOGLE_WORKSPACE", "Google Drive",
            "SELF_HOSTED_POSTGRES", "PostgreSQL",
            "AZURE_BLOB", "Azure Blob",
            "MAC", "macOS",
            "WINDOWS", "Windows");

    private static final Map<String, String> SCAN_STATUSES = Map.of(
            "NOT_SCANNED", "Not Scanned",
            "CLASSIFIED", "Classified",
            "FAILED", "Failed",
            "STOPPED", "Stopped",
            "SCANNING", "Scanning",
            "SCHEDULED", "Scheduled");

    private static final Map<String, String> RISK_LEVELS = Map.of(
            "CRITICAL", "Critical",
            "HIGH", "High",
            "MEDIUM", "Medium",
            "LOW", "Low");

    private static final Map<String, String> OS = Map.of(
            "MAC", "macOS",
            "WINDOWS", "Windows");

    private static final Map<String, String> AGENT_STATUSES = Map.of(
            "ONLINE", "Online",
            "OFFLINE", "Offline");

    private static final Map<String, String> SENSITIVITY_LEVELS = Map.of(
            "CRITICAL_SENSITIVITY", "Critical",
            "HIGH_SENSITIVITY", "High",
            "MEDIUM_SENSITIVITY", "Medium",
            "LOW_SENSITIVITY", "Low");

    /** Scan History rows report NOT_SENSITIVE for a run that found nothing; the console shows no label. */
    private static final String NOT_SENSITIVE = "NOT_SENSITIVE";

    private static final Map<String, String> FILE_TYPES = Map.ofEntries(
            Map.entry("unknown_files", "Other"),
            Map.entry("xlsx_files", "XLSX"),
            Map.entry("image_files", "Image"),
            Map.entry("pdf_files", "PDF"),
            Map.entry("documents", "Documents"),
            Map.entry("web_markup", "Web Markup"),
            Map.entry("compressed_files", "Compressed"),
            Map.entry("autocad", "Autocad"),
            Map.entry("code_files", "Code"),
            Map.entry("contact_files", "Contact"),
            Map.entry("databases_files", "Databases"),
            Map.entry("email", "Email"),
            Map.entry("log_files", "Log"),
            Map.entry("mailboxes", "Mailboxes"),
            Map.entry("parquet_files", "Parquet"),
            Map.entry("presentations", "Presentations"),
            Map.entry("system_generated_files", "System Generated"));

    private static final Map<String, String> PROPERTIES = Map.of(
            "region", "Region",
            "datastore_size", "Datastore Size",
            "arn", "ARN",
            "subscription_id", "Subscription ID",
            "resource_group", "Resource Group",
            "storage_account", "Storage Account",
            "host", "Host",
            "port", "Port");

    /** Words kept upper case when a property key is turned into a label. */
    static final Set<String> ACRONYMS =
            Set.of("id", "arn", "url", "ip", "ssl", "tls", "kms", "vpc", "db", "az", "cidr", "sku");

    private static final Map<String, String> SECURITY_CONFIGURATIONS = Map.of(
            "bucket_versioning", "Bucket Versioning",
            "default_encryption", "Default Encryption",
            "public_accessibility", "Public Accessibility",
            "logging", "Logging",
            "ssl_enforced", "SSL Enforced");

    private static final String FILES_SUFFIX = "_files";

    private static final Map<String, String> SAAS_SCAN_STATES = Map.of(
            "COMPLETED", "Completed",
            "DISABLED", "Disabled",
            "ENABLED", "Enabled",
            "IN_PROGRESS", "In Progress",
            "FAILED", "Failed");

    /** Seen in the console's Scan Summary; keys are lower-case extensions. */
    private static final Map<String, String> SAAS_FILE_TYPES = Map.of(
            "msg", "Email",
            "png", "Images",
            "pdf", "PDF");

    private static final Map<String, String> FILE_EXTENSION_DESCRIPTIONS = Map.of("msg", "Outlook Message");

    /** Words of a document category written in upper case. */
    private static final Set<String> UPPER_CATEGORY_WORDS = Set.of("it", "hr", "kyc");

    private static final Set<String> ONLINE_AGENT_STATUSES = Set.of("ACTIVE", "ONLINE");
    private static final Set<String> OFFLINE_AGENT_STATUSES = Set.of("INACTIVE", "OFFLINE");

    private static final Map<String, String> ENDPOINT_SCAN_STATUSES = Map.of(
            "COMPLETED", "Completed",
            "IN_PROGRESS", "In Progress",
            "TIMED_OUT", "Timed Out",
            "FAILED", "Failed");

    /** A15: extensions the console's Information chips write in upper case. */
    static final Set<String> KNOWN_UPPER_EXTENSIONS = Set.of("java", "tsx", "ts", "sql", "css", "yaml", "sh", "go",
            "md", "yml", "py", "json", "html", "js", "xml", "txt", "conf", "mjs", "csv", "docx", "mp3", "pdf");

    /**
     * Copy sources read as "Endpoint". The hash-lineage routes send {@code ENDPOINT_AGENT} for an
     * endpoint copy; {@code ENDPOINT} is the design's spelling.
     */
    private static final Set<String> ENDPOINT_SOURCES = Set.of("ENDPOINT", "ENDPOINT_AGENT");

    // Files page (plan C4, C5, C7, C12, C18): the console's wording.
    private static final Map<String, String> SHARING_MODES = Map.of(
            "PUBLIC", "Anyone with the link",
            "EXTERNAL", "Externally Shared",
            "INTERNAL", "Internally Shared",
            "PRIVATE", "Private");
    private static final Map<String, String> SHARING_FILTERS = Map.of(
            "internal", "Shared Internally",
            "external", "Shared Externally",
            "public", "Shared Publicly");
    private static final Map<String, String> LAST_MODIFIED = Map.of(
            "48h", "Last 48 hours",
            "7d", "Last 7 days",
            "30d", "Last 30 days");
    private static final Map<String, String> APP_TYPES = Map.of(
            "ONE_DRIVE", "OneDrive",
            "SHARE_POINT", "SharePoint",
            "OUTLOOK", "Outlook",
            "GOOGLE_WORKSPACE", "Google Drive",
            "GMAIL", "Gmail",
            "JIRA", "Jira",
            "CONFLUENCE", "Confluence",
            "GITLAB", "GitLab",
            "SLACK", "Slack");
    private static final Map<String, String> MIP_ACTIONS = Map.of(
            "TAG_APPLIED", "Tag applied",
            "TAG_CHANGED", "Tag changed",
            "TAG_REMOVED", "Tag removed",
            "TAG_SKIPPED", "Tag skipped",
            "USER_OVERRIDE", "User override");

    // ── list ─────────────────────────────────────────────────────────────────────────────────────

    /** FR-FLT-6: S3 → S3, SELF_HOSTED_POSTGRES → PostgreSQL (self-hosted); else title case. */
    public static String providerLabel(String code) {
        return lookup(PROVIDERS, code);
    }

    /** FR-TBL-3: the subtype map, then the provider map, then title case. */
    public static String subtypeLabel(String code) {
        String key = code(code);
        if (key == null) {
            return null;
        }
        String label = SUBTYPES.get(key);
        return label != null ? label : providerLabel(key);
    }

    /** The console's scan-status wording: CLASSIFIED → Classified, NOT_SCANNED → Not Scanned. */
    public static String scanStatusLabel(String code) {
        return lookup(SCAN_STATUSES, code);
    }

    /** CRITICAL → Critical … LOW → Low. */
    public static String riskLevelLabel(String level) {
        return lookup(RISK_LEVELS, level);
    }

    /** A7: as the risk level, plus NONE → No findings. */
    public static String severityOptionLabel(String value) {
        String key = code(value);
        return DspmLabels.SEVERITY_NONE.equals(key) ? "No findings" : riskLevelLabel(key);
    }

    /** MAC → macOS, WINDOWS → Windows. */
    public static String osLabel(String code) {
        return lookup(OS, code);
    }

    /** A12: ONLINE → Online, OFFLINE → Offline. */
    public static String agentStatusLabel(String code) {
        return lookup(AGENT_STATUSES, code);
    }

    /** Title case: laptop → Laptop. */
    public static String deviceTypeLabel(String value) {
        return value == null || value.isBlank() ? null : DspmLabels.prettify(value);
    }

    /**
     * A filter option's label (design 5.7, {@code optionLabel}). A blank value reads "-", except for
     * {@code data_element}, where it reads "Unclassified" as the vendor console does.
     * {@code integration_id} keeps the raw id until the account labeler replaces it.
     */
    public static String optionLabel(String dimension, String value) {
        if (value == null || value.isBlank()) {
            return "data_element".equals(dimension) ? "Unclassified" : "-";
        }
        return switch (dimension) {
            case "provider" -> providerLabel(value);
            case "scan_status" -> scanStatusLabel(value);
            case "severity" -> severityOptionLabel(value);
            case "sensitive_type" -> DspmLabels.sensitiveCategoryLabel(value);
            case "os" -> osLabel(value);
            case "agent_status" -> agentStatusLabel(value);
            case "device_type" -> deviceTypeLabel(value);
            // data_element, region, serial_number, hostname, user, selected, integration_id
            default -> value;
        };
    }

    // ── drawer ───────────────────────────────────────────────────────────────────────────────────

    /** Scan History severity: CRITICAL_SENSITIVITY → Critical; NOT_SENSITIVE → null. */
    public static String sensitivityLevelLabel(String code) {
        String key = code(code);
        if (key == null || NOT_SENSITIVE.equals(key)) {
            return null;
        }
        return lookup(SENSITIVITY_LEVELS, key);
    }

    /** A23, Scan History status: SCAN_STOPPED → Stopped; others title case. */
    public static String scanRunStatusLabel(String code) {
        String key = code(code);
        return "SCAN_STOPPED".equals(key) ? "Stopped" : prettifyOrNull(key);
    }

    /** Overview scanning status: SCAN_STOPPED → Scan Stopped, SCANNING_OUTPUT_PROCESSED → Scanning Output Processed. */
    public static String overviewScanningStatusLabel(String code) {
        String key = code(code);
        return "SCAN_STOPPED".equals(key) ? "Scan Stopped" : prettifyOrNull(key);
    }

    /** Misconfiguration severity, case-insensitive: medium → Medium. */
    public static String misconfigurationSeverityLabel(String severity) {
        return lookup(RISK_LEVELS, severity);
    }

    /** FR-INF-1 file types; an unknown key is the title case of the key without a {@code _files} suffix. */
    public static String fileTypeLabel(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String label = FILE_TYPES.get(key);
        if (label != null) {
            return label;
        }
        String base = key.endsWith(FILES_SUFFIX) && key.length() > FILES_SUFFIX.length()
                ? key.substring(0, key.length() - FILES_SUFFIX.length())
                : key;
        return DspmLabels.prettify(base);
    }

    /**
     * A19 property labels. Known keys first; any other key is split on {@code _}, words in
     * {@link #ACRONYMS} upper-cased and every other word capitalised ({@code kms_key_id} → "KMS Key ID").
     * camelCase keys are not split.
     */
    public static String propertyLabel(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String known = PROPERTIES.get(key);
        if (known != null) {
            return known;
        }
        StringBuilder out = new StringBuilder();
        for (String word : key.trim().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            if (ACRONYMS.contains(word.toLowerCase(Locale.ROOT))) {
                out.append(word.toUpperCase(Locale.ROOT));
            } else {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.isEmpty() ? key : out.toString();
    }

    /** The five security configurations the console lists; null for any other key. */
    public static String securityConfigurationLabel(String key) {
        return key == null ? null : SECURITY_CONFIGURATIONS.get(key);
    }

    /** PII → Personal, SECRETS → IT &amp; Security (delegates to {@link DspmLabels}). */
    public static String sensitiveCategoryLabel(String code) {
        return DspmLabels.sensitiveCategoryLabel(code);
    }

    // ── SaaS drawer and SaaS file drawer ─────────────────────────────────────────────────────────

    /** Backfill and incremental scan states: IN_PROGRESS → In Progress. */
    public static String saasScanStateLabel(String code) {
        return lookup(SAAS_SCAN_STATES, code);
    }

    /** Scan Summary file types: msg → Email, png → Images, pdf → PDF; any other extension upper-cased (GAP-56). */
    public static String saasFileTypeLabel(String extension) {
        String key = extensionKey(extension);
        if (key == null) {
            return null;
        }
        String label = SAAS_FILE_TYPES.get(key);
        return label != null ? label : key.toUpperCase(Locale.ROOT);
    }

    /** The file drawer's File Type: msg → Outlook Message; any other extension upper-cased. */
    public static String fileExtensionDescription(String extension) {
        String key = extensionKey(extension);
        if (key == null) {
            return null;
        }
        String label = FILE_EXTENSION_DESCRIPTIONS.get(key);
        return label != null ? label : key.toUpperCase(Locale.ROOT);
    }

    /**
     * A document category: "it &amp; technical" → "IT &amp; Technical", "identity &amp; personal" →
     * "Identity &amp; Personal". Words in {it, hr, kyc} are upper-cased, every other word capitalised.
     */
    public static String documentCategoryLabel(String code) {
        return words(code, word -> UPPER_CATEGORY_WORDS.contains(word.toLowerCase(Locale.ROOT))
                ? word.toUpperCase(Locale.ROOT)
                : capitalise(word));
    }

    /** Metadata keys: the first character upper-cased, the rest lower-cased ({@code threadId} → "Threadid"). */
    public static String metadataLabel(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return capitalise(key.trim());
    }

    /**
     * Each word's first letter upper-cased and the rest lower-cased: "it &amp; technical" → "It &amp;
     * Technical" (the Endpoint File View's spelling, GAP-51).
     */
    public static String titleCase(String value) {
        return words(value, DatastoreLabels::capitalise);
    }

    /**
     * A copy's channel or source: the drive type's label when set (GMAIL → Gmail, GOOGLE_WORKSPACE →
     * Google Drive), else "Endpoint" for an endpoint source or a copy with an OS, else the source
     * prettified.
     */
    public static String copySourceLabel(String source, String driveType, String os) {
        if (code(driveType) != null) {
            return subtypeLabel(driveType);
        }
        String sourceKey = code(source);
        if ((sourceKey != null && ENDPOINT_SOURCES.contains(sourceKey)) || code(os) != null) {
            return "Endpoint";
        }
        return prettifyOrNull(sourceKey);
    }

    /**
     * A copy's location, "&lt;source&gt; · &lt;detail&gt;": "SaaS · Gmail", "SaaS · Google Drive",
     * "Endpoint · macOS". The detail is the drive type's label, else the OS; without one, the source alone.
     */
    public static String copyLocationLabel(String source, String driveType, String os) {
        String sourceKey = code(source);
        String sourceLabel;
        if (sourceKey == null) {
            sourceLabel = null;
        } else if ("SAAS".equals(sourceKey)) {
            sourceLabel = "SaaS";
        } else if (ENDPOINT_SOURCES.contains(sourceKey)) {
            sourceLabel = "Endpoint";
        } else {
            sourceLabel = DspmLabels.prettify(sourceKey);
        }
        String detail = code(driveType) != null ? subtypeLabel(driveType) : osLabel(os);
        if (sourceLabel == null) {
            return detail;
        }
        return detail == null ? sourceLabel : sourceLabel + " \u00B7 " + detail;
    }

    // ── Endpoint drawer ──────────────────────────────────────────────────────────────────────────

    /** ACTIVE and ONLINE → Online, INACTIVE and OFFLINE → Offline; other values prettified (GAP-49). */
    public static String endpointAgentStatusLabel(String code) {
        String key = code(code);
        if (key == null) {
            return null;
        }
        if (ONLINE_AGENT_STATUSES.contains(key)) {
            return "Online";
        }
        if (OFFLINE_AGENT_STATUSES.contains(key)) {
            return "Offline";
        }
        return DspmLabels.prettify(key);
    }

    /** True for ACTIVE and ONLINE only. */
    public static boolean endpointAgentOnline(String code) {
        String key = code(code);
        return key != null && ONLINE_AGENT_STATUSES.contains(key);
    }

    /** Endpoint Scan History: TIMED_OUT → Timed Out. */
    public static String endpointScanStatusLabel(String code) {
        return lookup(ENDPOINT_SCAN_STATUSES, code);
    }

    /**
     * An extension chip: upper case for the extensions the console writes so (A15), any other with its
     * first letter upper-cased and the rest lower-cased ("Properties", "Bak3", "Ps1").
     */
    public static String extensionLabel(String extension) {
        String key = extensionKey(extension);
        if (key == null) {
            return null;
        }
        return KNOWN_UPPER_EXTENSIONS.contains(key) ? key.toUpperCase(Locale.ROOT) : capitalise(key);
    }

    // ── Files page ───────────────────────────────────────────────────────────────────────────────

    /**
     * A category or document class chip (plan C10, the console chip's CSS capitalize): each space-separated
     * word's first letter upper-cased, the rest kept. "other" → "Other", "it &amp; technical" → "It &amp;
     * Technical", "QA and Release" → "QA And Release"; null when blank.
     */
    public static String categoryChipLabel(String value) {
        return words(value, word -> Character.toUpperCase(word.charAt(0)) + word.substring(1));
    }

    /** A SaaS row's sharing mode (plan C7): PUBLIC "Anyone with the link" … PRIVATE "Private". */
    public static String sharingModeLabel(String code) {
        return lookup(SHARING_MODES, code);
    }

    /** The Sharing Mode filter's options (plan C4): internal "Shared Internally" …; null for any other value. */
    public static String sharingFilterLabel(String value) {
        return value == null ? null : SHARING_FILTERS.get(value);
    }

    /** The Last Modified filter's options: 48h "Last 48 hours", 7d "Last 7 days", 30d "Last 30 days". */
    public static String lastModifiedLabel(String value) {
        return value == null ? null : LAST_MODIFIED.get(value);
    }

    /** A SaaS row's app (plan C12): GOOGLE_WORKSPACE → Google Drive, GITLAB → GitLab; others prettified. */
    public static String appTypeLabel(String code) {
        return lookup(APP_TYPES, code);
    }

    /** The App Type filter's label (plan C5): as {@link #appTypeLabel}, but GITLAB reads "Gitlab". */
    public static String appTypeOptionLabel(String code) {
        String key = code(code);
        return "GITLAB".equals(key) ? "Gitlab" : appTypeLabel(key);
    }

    /** A MIP audit action (plan C18): TAG_APPLIED → "Tag applied" …; any other code as it is. */
    public static String mipActionLabel(String code) {
        String key = code(code);
        if (key == null) {
            return null;
        }
        String label = MIP_ACTIONS.get(key);
        return label != null ? label : code;
    }

    /**
     * A data element filter option (plan C6): sentence case within each {@code _}-separated part, "EMAIL
     * ADDRESS" → "Email address"; null when blank.
     */
    public static String dataElementOptionLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split("_", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('_');
            }
            if (!parts[i].isEmpty()) {
                out.append(capitalise(parts[i]));
            }
        }
        return out.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static String code(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String lookup(Map<String, String> map, String raw) {
        String key = code(raw);
        if (key == null) {
            return null;
        }
        String label = map.get(key);
        return label != null ? label : DspmLabels.prettify(key);
    }

    private static String prettifyOrNull(String key) {
        return key == null ? null : DspmLabels.prettify(key);
    }

    /** An extension as a lookup key: trimmed and lower-cased; null when blank. */
    private static String extensionKey(String extension) {
        return extension == null || extension.isBlank() ? null : extension.trim().toLowerCase(Locale.ROOT);
    }

    /** The first character upper-cased, the rest lower-cased: "bak3" → "Bak3", "&amp;" stays "&amp;". */
    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Splits on spaces, applies {@code rule} to each word and joins with single spaces; null when blank. */
    private static String words(String value, UnaryOperator<String> rule) {
        if (value == null || value.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(rule.apply(word));
        }
        return out.toString();
    }
}
