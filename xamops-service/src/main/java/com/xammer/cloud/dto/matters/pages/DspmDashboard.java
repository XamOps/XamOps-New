package com.xammer.cloud.dto.matters.pages;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;

import java.util.List;
import java.util.Map;

/**
 * The XamLens Dashboard (design 5.4, brief 10.1).
 *
 * @param environments in the order cloud, saas, endpoint, on_prem
 * @param topRisky     keys all, cloud, saas, endpoint, on_prem: up to 5 labelled rows with records, by severity
 * @param regions      the "Active Sensitive Regions" (brief 11), most sensitive records first
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DspmDashboard(List<Environment> environments, Map<String, List<DatastoreRow>> topRisky,
                            List<Region> regions) {

    /**
     * One "Where does sensitive data live" card.
     *
     * @param recordsTrend the tab's daily sensitive-record totals from {@code /assets/summary}, oldest first
     *                     (the card's "vs last week" line, brief 10.1)
     * @param filter       the Datastores filter a bar opens: "provider" (cloud, on_prem), "os" (endpoint), or
     *                     null (saas, whose bars are drive types and open nothing)
     * @param partial      the bars cover only the first rows of a large environment
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Environment(String id, long datastores, long sensitiveRecords, List<DspmSummary.Point> recordsTrend,
                              boolean connected, String filter, List<SubCategory> subCategories, boolean partial) {
    }

    /** One bar: the provider (cloud, on_prem), drive type (saas) or OS (endpoint) code and its sums. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SubCategory(String key, String label, long sensitiveRecords, long datastores) {
    }

    /**
     * One "Active Sensitive Regions" region: the collected cloud rows of one {@code source.region}, listed
     * when at least one of them has sensitive records or is classified (the console's tooltip: "active cloud
     * datastores with detected sensitive or classified data").
     *
     * @param region            the raw {@code source.region} code (the frontend labels and places it)
     * @param scannedDatastores the rows whose scan status is CLASSIFIED
     * @param sensitiveLabels   the rows' sensitive category labels, the most common first
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Region(String region, long datastores, long scannedDatastores, long sensitiveRecords,
                         List<String> sensitiveLabels) {
    }
}
