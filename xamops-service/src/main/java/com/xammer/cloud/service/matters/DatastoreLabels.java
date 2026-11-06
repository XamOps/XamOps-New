package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
}
