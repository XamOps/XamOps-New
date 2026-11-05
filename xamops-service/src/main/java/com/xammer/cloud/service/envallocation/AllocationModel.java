package com.xammer.cloud.service.envallocation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of environment allocation: what a rule matches on, what it does with a match,
 * and the bill lines it is applied to.
 *
 * <p>These types are the JSON shape of both the editable draft and every published version, so
 * renaming a constant here changes how stored versions read back. Add, do not rename.
 */
public final class AllocationModel {

    private AllocationModel() {
    }

    /** Name and colour of the bucket for spend no rule matched. Never a client environment. */
    public static final String UNALLOCATED = "Unallocated";
    public static final String UNALLOCATED_COLOR = "#9CA3AF";

    /** Which part of a bill line a rule looks at. */
    public enum MatchField {
        /** Every line. The usual last rule: "everything else goes to Shared". */
        ANY,
        /** The product name on the bill, e.g. "Amazon Elastic Compute Cloud" or a Bedrock model. */
        SERVICE,
        /** e.g. {@code USE1-SpotUsage:m5.large}, {@code USE1-Claude4.6Sonnet-input-tokens}. */
        USAGE_TYPE,
        REGION,
        /** The 12-digit usage account. */
        ACCOUNT,
        /** {@code Usage}, {@code Tax}, {@code Credit}, {@code Refund}, {@code SavingsPlanCoveredUsage}, ... */
        CHARGE_TYPE,
        /** The CUR resource id: an instance id, a bucket name, an ARN. */
        RESOURCE_ID,
        /** One AWS tag, read from the report. Only cost-allocation tags reach a CUR. */
        TAG,
        /** XamOps resource labels, which apply to past months because they join on resource id. */
        LABEL
    }

    /** How the field is compared to the rule's value. Every comparison ignores case. */
    public enum MatchOperator {
        EQUALS,
        CONTAINS,
        STARTS_WITH,
        /** The field has any non-blank value. For a label: the resource carries any label. */
        EXISTS
    }

    /** What a matched line turns into. */
    public enum AllocationType {
        /** Fixed percentages, adding up to 100. One entry at 100% is a whole assignment. */
        FIXED,
        /**
         * The matched tag value or label name picks the environment — {@code Environment=prod}
         * goes to whichever environment is named, or lists, {@code prod}. A value that names no
         * environment does not match, and the line falls through to the next rule.
         */
        FROM_VALUE,
        /**
         * Shared in proportion to what every environment received from the other rules. For lines
         * that belong to everyone — tax, support, enterprise discounts.
         */
        PROPORTIONAL
    }

    /** Why an amount landed where it did. Stored on every result row. */
    public enum AllocationKind {
        /** A tag value, label name or other matched value chose the environment. */
        TAGGED,
        /** A rule sent the whole amount to one environment. */
        RULE,
        /** A rule split the amount by fixed percentages. */
        SPLIT,
        /** Shared in proportion to the other allocations. */
        PROPORTIONAL,
        /** No rule matched. */
        UNALLOCATED;

        /** Direct means the amount was not divided: a tag, a label, or a 100% rule. */
        public boolean isDirect() {
            return this == TAGGED || this == RULE;
        }
    }

    /** One environment's share inside a FIXED rule. */
    public record SplitSpec(Long environmentId, String environmentName, BigDecimal percent) {
    }

    /**
     * One rule. Rules are evaluated top to bottom and the first match takes the line.
     *
     * @param id     stable across edits, so a stored result row can be traced back to its rule
     * @param value  compared against the field; ignored for {@code ANY} and {@code EXISTS}
     * @param tagKey the tag to read when {@code field} is {@code TAG}
     */
    public record RuleSpec(String id,
                           String name,
                           MatchField field,
                           MatchOperator operator,
                           String value,
                           String tagKey,
                           AllocationType allocation,
                           List<SplitSpec> splits) {
    }

    /** An environment as a rule version saw it. */
    public record EnvironmentSpec(Long id, String name, String color, List<String> matchValues) {
    }

    /**
     * One grouped line of the bill.
     *
     * @param resourceId blank when the report was read without resource grain, or the charge
     *                   belongs to no resource (tax, support)
     * @param tags       tag key, as the rule names it, to value; only the keys some rule reads
     * @param labels     XamOps label names on the resource
     */
    public record CostLine(String account,
                           String service,
                           String region,
                           String usageType,
                           String chargeType,
                           String resourceId,
                           Map<String, String> tags,
                           Set<String> labels,
                           BigDecimal cost) {
    }
}
