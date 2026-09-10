package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical forms for the identifiers Upwind's two inventory endpoints disagree on.
 *
 * The catalog search ({@code /inventory/catalog/assets/search}) and the relationship search
 * ({@code /inventory/assets/search}) describe the same assets — verified 2026-09-07, they share
 * one {@code uwr-} id space, 1,426 ids in common for account 605134457560 — but they do not
 * agree on how they spell anything else:
 *
 * <pre>
 *                     catalog                              relationship
 * cloud_resource_id   arn:aws:ec2:…:instance/i-009aa3a…    i-0613ecd23d0912db7
 * cloud_provider      aws                                  AWS
 * status              ACTIVE                               running
 * asset type          resource_type                        label
 * category            category / sub_category              asset_category / asset_sub_category
 * </pre>
 *
 * A lookup by the catalog's {@code cloud_resource_id} against the relationship search returns
 * zero items, which is a silent miss rather than an error. That class of defect has bitten this
 * integration repeatedly (the Link-header comma that stopped pagination at page 1, PASS findings
 * never fetched, shift-left returning the oldest scans), so the reconciliation lives in one
 * place and every join goes through it.
 *
 * Measured distribution of {@code cloud_resource_id} over 2,481 relationship-search assets in
 * one account: full ARN 1,181, bare provider id 830, composite {@code account::region::id} 5,
 * empty 465 (the empties are sub-entities — tags, security-group rules, listeners — which are
 * not cloud resources at all).
 */
public final class UpwindAssetIds {

    private UpwindAssetIds() {
    }

    /**
     * The shortest form of a resource id that both endpoints can be joined on.
     *
     * Intended for JOINING, not for display: for a resource whose ARN ends in a generated
     * suffix (an ALB's {@code …:loadbalancer/app/my-alb/b014d8e07a79aa45}) this returns the
     * suffix, which is unique but not human-friendly. Use {@code name} for display.
     *
     * @return the canonical id, or null when there is nothing to canonicalise
     */
    public static String canonicalResourceId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();

        // Composite form: 605134457560::ap-south-2::vpc-0d729a800a19ad06d. Only 5 of 2,481
        // assets used it, all VPCs, and it would otherwise never match the bare vpc-… that
        // security groups and ENIs carry in vpc_id.
        int composite = v.lastIndexOf("::");
        if (composite >= 0) {
            String tail = v.substring(composite + 2).trim();
            if (!tail.isEmpty()) return tail;
        }

        if (v.startsWith("arn:")) {
            // Take whichever separator comes last, so both
            // arn:…:network-interface/eni-00f2… -> eni-00f2…   (slash)
            // arn:aws:s3:::my-bucket             -> my-bucket   (colon)
            // resolve to the segment that actually identifies the resource.
            int cut = Math.max(v.lastIndexOf('/'), v.lastIndexOf(':'));
            if (cut >= 0 && cut < v.length() - 1) return v.substring(cut + 1);
        }
        return v;
    }

    /** True when two raw ids refer to the same resource once canonicalised. */
    public static boolean sameResource(String a, String b) {
        String ca = canonicalResourceId(a);
        String cb = canonicalResourceId(b);
        return ca != null && ca.equals(cb);
    }

    /** Provider, uppercased — the catalog says {@code aws}, the relationship search {@code AWS}. */
    public static String provider(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase();
    }

    /**
     * The asset's type label (e.g. {@code aws_ec2_instance}), from whichever field the endpoint
     * used. This is the key into the inventory schema, so it has to resolve for both payloads.
     */
    public static String assetType(JsonNode node) {
        return first(node, "label", "resource_type");
    }

    /** Top-level category, e.g. {@code networking}. */
    public static String category(JsonNode node) {
        return first(node, "category", "asset_category");
    }

    /** Sub-category, e.g. {@code virtual_private_cloud}. */
    public static String subCategory(JsonNode node) {
        return first(node, "sub_category", "asset_sub_category");
    }

    /**
     * Deliberately absent: a unified {@code status}.
     *
     * The catalog's {@code ACTIVE} is Upwind's own sync lifecycle (is this asset still present)
     * while the relationship search's {@code running} is the provider's instance state. They
     * answer different questions and mapping one onto the other would invent a fact. Callers
     * that need both should read both, under distinct names.
     */

    private static String first(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
