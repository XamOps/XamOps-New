package com.xammer.cloud.dto.matters;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One row of the DSPM asset list, as XamOps serves it.
 *
 * <p>Typed records rather than the {@code Map<String, Object>} the XamSecure service returns. The maps
 * there mean the response shape is only discoverable by reading the code that builds it, and the
 * frontend's 997-line hand-written type file is the only description of the contract. Records make the
 * contract the declaration.
 *
 * <p>The nesting deliberately mirrors the vendor's own grouping ({@code risk}, {@code sensitives},
 * {@code source}, {@code scan_status}) so the mapping stays reviewable field-for-field against the API.
 * The nested types are reused by {@link DspmAssetDetail}.
 *
 * <p>Two fields carry information the vendor payload does not state directly, computed once here so
 * every consumer agrees: {@link Sensitives#known()} and {@link Risk#hasFinding()}.
 *
 * @param id            stable Matters UUID. Safe to store; use it for the detail lookup.
 * @param name          display name.
 * @param subtype       provider or device type, e.g. {@code S3}, {@code SELF_HOSTED_POSTGRES}.
 * @param subtypeLabel  the same, prettified for display.
 * @param category      {@code cloud}, {@code on_prem}, {@code saas} or {@code endpoint}.
 * @param resourceType  {@code DATASTORE}, {@code SAAS_PRINCIPAL} or {@code ENDPOINT_HOST}.
 * @param sourceId      the asset's external identifier (ARN, bucket name, email, serial). Undocumented
 *                      but present on the list, which saves a detail fetch per row to show it.
 * @param lifecycle     {@code ACTIVE} or {@code NOT_APPLICABLE}. Also undocumented.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DspmAssetRow(
        String id,
        String name,
        String subtype,
        String subtypeLabel,
        String category,
        String resourceType,
        String sourceId,
        String lifecycle,
        Risk risk,
        Sensitives sensitives,
        Source source,
        Scan scan,
        Profile profile
) {

    /** The same row with its detail-only fields attached. */
    public DspmAssetRow withProfile(Profile value) {
        return new DspmAssetRow(id, name, subtype, subtypeLabel, category, resourceType, sourceId,
                lifecycle, risk, sensitives, source, scan, value);
    }

    /**
     * Fields the vendor returns on asset detail only, attached to list rows where the table needs them.
     *
     * Null unless the surface asks for it (SaaS today). The SaaS table's Assets, Total Files and Total
     * Size columns are the account email ({@code external_id}) and {@code facet.drive_type},
     * {@code facet.total_inventories} and {@code facet.total_size_bytes}, none of which are on the list.
     *
     * @param externalId the asset's external identifier; the account email for SaaS.
     * @param facet      the detail's type-specific attributes, untyped for the same reason as
     *                   {@link DspmAssetDetail#facet()}.
     * @param recordCount the record count after removing duplicate object listings, when the vendor's
     *                    list figure is known to include them (endpoints); null otherwise.
     */
    public record Profile(String externalId, java.util.Map<String, Object> facet, Long recordCount) {
    }

    /**
     * @param score                numeric risk score, null when not evaluated (90 of 100 rows in the
     *                             test project).
     * @param level                severity band, never null here — a null from the vendor is folded to
     *                             {@code NONE} so it matches the value the filter dimension uses.
     * @param policyViolationCount failing policy checks. Independent of {@link #level} and
     *                             {@link #score}: one live asset carries 35 violations with both of
     *                             those null, so neither can be inferred from the other.
     * @param hasFinding           whether {@link #level} is a real band rather than {@code NONE}.
     */
    public record Risk(Double score, String level, Integer policyViolationCount, boolean hasFinding) {
    }

    /**
     * @param recordCount  sensitive records found. Meaningless unless {@link #known} is true.
     * @param objectCount  <b>total</b> objects discovered under the asset, not the sensitive subset.
     *                     Named as the vendor names it; the UI column is labelled "Objects" for this
     *                     reason.
     * @param types        raw category codes, e.g. {@code ["PII","SECRETS"]}.
     * @param typeLabels   the same in console wording, e.g. {@code ["Personal","IT & Security"]}.
     * @param level        overall sensitivity classification, null until classified.
     * @param known        whether {@link #recordCount} can be believed — true only when the scan
     *                     reached {@code CLASSIFIED}. False means not scanned, still scanning, failed
     *                     or stopped, and the UI must say so rather than printing a zero.
     */
    public record Sensitives(long recordCount, long objectCount, List<String> types,
                             List<String> typeLabels, String level, boolean known) {
    }

    /**
     * @param provider platform the asset lives on.
     * @param account  the Matters <b>integration</b> id, not a cloud account number. The vendor
     *                 console resolves this to a friendly name ("Xammer-prod"); the API does not expose
     *                 that mapping, so the id is the most we can show on a list row.
     * @param region   cloud region, or {@code self-hosted} for on-prem. Null for ~half of rows.
     */
    public record Source(String provider, String providerLabel, String account, String region) {
    }

    /**
     * @param status        raw status. The live set is {@code NOT_SCANNED}, {@code CLASSIFIED},
     *                      {@code FAILED}, {@code STOPPED}, {@code SCANNING} — note
     *                      {@code NOT_SCANNED} is undocumented and {@code SCHEDULED} never appears.
     * @param statusLabel   console wording, e.g. "Scan stopped".
     * @param stage         finer-grained progress, e.g. {@code SCANNING_OUTPUT_PROCESSED}. Undocumented.
     * @param lastScannedAt Unix epoch <b>seconds</b>, UTC. Null for 90 of 100 live rows.
     */
    public record Scan(String status, String statusLabel, String stage, Long lastScannedAt) {
    }
}
