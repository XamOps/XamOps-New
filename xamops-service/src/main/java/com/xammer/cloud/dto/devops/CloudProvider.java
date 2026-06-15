package com.xammer.cloud.dto.devops;

/**
 * Supported cloud providers for DevOps scripts.
 *
 * Each enum constant carries the canonical lowercase code that is persisted on
 * {@code DevOpsScript.cloudProviders}.
 *
 * Requirements: REQ-2.1.2 (Multi-Cloud Coverage), REQ-2.1.3 (Script Categorization)
 */
public enum CloudProvider {

    AWS("aws"),
    AZURE("azure"),
    GCP("gcp"),
    MULTI_CLOUD("multi-cloud");

    private final String code;

    CloudProvider(String code) {
        this.code = code;
    }

    /** The persisted/indexed string representation (e.g. {@code "aws"}). */
    public String getCode() {
        return code;
    }

    /**
     * Resolve a {@link CloudProvider} from its persisted code, case-insensitively.
     *
     * @param code the stored provider code (e.g. {@code "aws"}, {@code "multi-cloud"})
     * @return the matching provider, or {@code null} if none matches
     */
    public static CloudProvider fromCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (CloudProvider provider : values()) {
            if (provider.code.equals(normalized)) {
                return provider;
            }
        }
        return null;
    }
}
