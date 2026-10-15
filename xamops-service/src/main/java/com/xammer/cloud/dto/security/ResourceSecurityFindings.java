package com.xammer.cloud.dto.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every security, compliance and performance finding that references one resource, grouped by the
 * product that owns it and sorted most-severe-first within each group.
 *
 * <p>Feeds the Cost drawer's "Security &amp; Alerts" tab. The tab shows what SecOps surfaces know
 * about the resource the reader is looking at the bill for, and each row deep-links back to the
 * product page that owns the finding, filtered to the resource.
 *
 * <p>Shaped like {@code DashboardData}'s nested DTOs — Lombok {@code @Data} with the no-arg and
 * all-args constructors, {@code @JsonInclude(NON_NULL)} — so the wire form is stable and null
 * optional fields drop out rather than serialising as {@code null}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceSecurityFindings {

    /** The resource id as asked about, echoed back so the client can match the answer to its request. */
    private String resourceId;

    /**
     * Whether the resource id resolved to a known inventory row.
     *
     * <p>Independent of whether any findings came back: a resolved resource with no findings is a
     * clean resource, an unresolved one is one the inventory does not know. The tab words the two
     * empty states differently.
     */
    private boolean matched;

    /** One group per product that had at least one finding, product order fixed by the aggregator. */
    private List<ResourceSecurityGroup> groups = new ArrayList<>();

    /**
     * One entry per product whose read failed, so a single product outage degrades to a line in the
     * tab rather than a 500 for the whole response.
     */
    private List<ProductError> errors = new ArrayList<>();

    /** One product's findings for the resource. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResourceSecurityGroup {
        /** Stable product code: {@code SECURITY_CENTER}, {@code COMPLIANCEOPS}, {@code PERFORMANCE}. */
        private String product;
        /** Human label for the group header, e.g. "Security Center". */
        private String label;
        /** The product page's route, e.g. {@code /security}, for the group's own link. */
        private String route;
        private List<ResourceSecurityFinding> findings = new ArrayList<>();
    }

    /** One finding against the resource, with where to go to see it in full. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResourceSecurityFinding {
        private String title;
        /** Normalised via {@link com.xammer.cloud.service.security.SecuritySeverity}. */
        private String severity;
        /** Optional extra context shown under the title. */
        private String detail;
        private SecurityDeepLink deepLink;
    }

    /**
     * Where a finding row navigates to: the product page path plus the query that filters it to the
     * resource. The client builds a react-router target from these two fields.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SecurityDeepLink {
        private String path;
        private Map<String, String> query;
    }

    /** A product read that failed, named so the tab can say which one and why. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProductError {
        private String product;
        private String message;
    }
}
