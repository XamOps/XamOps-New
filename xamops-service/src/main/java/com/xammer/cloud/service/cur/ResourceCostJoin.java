package com.xammer.cloud.service.cur;

import com.xammer.cloud.service.ResourceIdResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connectivity join's arithmetic: CUR groups in, per-requested-id costs out.
 *
 * <p>A pure class with no collaborators by design. Attribution is the part of this feature most
 * likely to be quietly wrong — a cost landing on the wrong resource is indistinguishable from a
 * real one once it is on screen — so it is testable without Athena, Spring or a database.
 */
public final class ResourceCostJoin {

    /**
     * Below this a figure is rounding rather than money.
     *
     * <p>The query's own {@code HAVING ABS(SUM(cost)) > 0.001} cannot see a cancellation that
     * straddles two candidate forms: a resource billed +5.00 under its bare id and −5.00 under its
     * ARN survives as two groups and sums to zero here. A present-and-zero entry would render as
     * {@code $0.00}, which is the one figure the connectivity tab must never show, so such an id is
     * dropped from {@code costs} entirely and reads as "no cost attributable" instead.
     */
    private static final double MIN_COST = 0.001;

    private ResourceCostJoin() {
    }

    /**
     * Attributes each CUR group to the requested ids whose candidate set contains its identifier.
     *
     * <p>Several candidates of one resource can both appear in the report — rows written under a
     * bare id early in the month and under an ARN later — and they sum into one figure per
     * requested id. Two requested ids resolving to one inventory row are aliases and both receive
     * the figure, which is why {@code owners()} returns a list. A group nobody asked about is
     * ignored: the query only asked about candidates, so that can only happen if Athena returns
     * something it was not asked for.
     *
     * @param requested  the cleaned requested ids, in request order
     * @param candidates the identifier resolution, which also decides what is unanswerable
     * @param groups     what the batch query returned
     * @param basis      echoed back so a client can tell which basis a cached payload was computed
     *                   on
     * @return one entry in {@code costs} per requested id with a non-zero attributable figure,
     *         cost-descending
     */
    public static ResourceCostsResponse join(List<String> requested,
                                             ResourceIdResolver.CurIdentifiers candidates,
                                             CurAdvancedMetricsService.ResourceGroupCosts groups,
                                             CostBasis basis) {
        Map<String, List<String>> owners = candidates.owners();
        Map<String, Double> perId = new LinkedHashMap<>();
        for (CurAdvancedMetricsService.ResourceGroupCost group : groups.groups()) {
            for (String owner : owners.getOrDefault(group.curResourceId(), List.of())) {
                perId.merge(owner, group.cost(), Double::sum);
            }
        }

        List<ResourceCostEntry> costs = new ArrayList<>();
        for (String id : requested) {
            Double cost = perId.get(id);
            if (cost != null && Math.abs(cost) > MIN_COST) {
                costs.add(new ResourceCostEntry(id, cost));
            }
        }
        costs.sort(Comparator.comparingDouble(ResourceCostEntry::cost).reversed());

        return new ResourceCostsResponse(costs, List.copyOf(candidates.ambiguous()),
                requested.size(), costs.size(), groups.totalCost(), groups.groupCount(),
                basis.wire());
    }

    /**
     * Which resource each requested id names, and which graph node ids could be it.
     *
     * <p>Takes the whole resolution rather than a descriptive map, because
     * {@code nodeIdCandidates} comes from the locate list and the descriptive fields come from the
     * resolved rows — two different maps, and only one of them is filtered by the join invariant.
     * Handing the pricing list over as {@code nodeIdCandidates} would send an unanswerable id to
     * the client with no candidates at all, which renders as "not in the account topology": the one
     * unavailable state with no retry path, and a false statement about a resource that is in the
     * graph.
     *
     * <p>Every requested id gets an entry, including the ones the inventory does not know. Absence
     * would be indistinguishable from the server having dropped the id, and the client needs to
     * tell "the inventory does not know this" from "something went wrong".
     *
     * @param requested  the cleaned requested ids, in request order
     * @param candidates the identifier resolution
     */
    public static ResourceIdentities identities(List<String> requested,
                                                ResourceIdResolver.CurIdentifiers candidates) {
        List<ResourceIdentity> identities = new ArrayList<>();
        int resolved = 0;
        for (String id : requested) {
            ResourceIdResolver.CanonicalResource row = candidates.resolved().get(id);
            if (row != null) {
                resolved++;
            }
            identities.add(new ResourceIdentity(
                    id,
                    candidates.nodeIdCandidatesByRequestedId().getOrDefault(id, List.of(id)),
                    row == null ? null : row.resourceId(),
                    row == null ? null : row.resourceType(),
                    row == null ? null : row.resourceName(),
                    row == null ? null : row.region()));
        }
        return new ResourceIdentities(identities, requested.size(), resolved);
    }

    /**
     * One requested id's cost for the month.
     *
     * @param resourceId the id <em>as requested</em>, not the CUR id it matched — the client asked
     *                   about graph ids and has to be able to look its rows up by them
     * @param cost       every charge type, across every service and region, on the requested basis
     */
    public record ResourceCostEntry(String resourceId, double cost) {
    }

    /**
     * What a set of resources cost, and what could not be answered about them.
     *
     * @param costs          one entry per priced requested id, cost-descending. An id absent from
     *                       both this and {@code ambiguous} has no attributable cost, which the
     *                       client renders as a sentence and never as a zero.
     * @param ambiguous      requested ids with no usable identifier — two live resources answer to
     *                       the name, so pricing either would be a guess
     * @param requestedCount how many ids were asked about
     * @param pricedCount    how many came back with a figure, so the client can state the gap in
     *                       its own words rather than leaving a reader to find it by subtracting
     * @param totalCost      the matched set's total from the query's unpartitioned window. Not the
     *                       sum of {@code costs} in the alias case, where one resource is priced
     *                       under two names.
     * @param groupCount     how many CUR groups matched
     * @param basis          the basis these figures were computed on, in its wire form
     */
    public record ResourceCostsResponse(List<ResourceCostEntry> costs, List<String> ambiguous,
                                        int requestedCount, int pricedCount,
                                        double totalCost, long groupCount, String basis) {
    }

    /**
     * Which resource one requested id names.
     *
     * @param requestedId      the id as asked about, so a client matches by value rather than by
     *                         position
     * @param nodeIdCandidates every identifier the inventory holds for the resource, in the order a
     *                         client should try them against a graph index — {@code resource_id}
     *                         first, because that is what the inventory-plane graph path emits.
     *                         Never empty: an unresolved id carries itself.
     * @param resourceId       the inventory's short id, for display. <b>Not</b> "the graph id" — it
     *                         is always the ARN tail, which for a load balancer is
     *                         {@code 50dc6c495c0c9188} while the graph may name the node
     *                         {@code my-lb}. Null when the inventory does not know the id.
     * @param resourceType     the UI-facing type ("EC2 Instance"), or null
     * @param resourceName     the resource's name, or null
     * @param region           the region, or null for a global resource
     */
    public record ResourceIdentity(String requestedId, List<String> nodeIdCandidates,
                                   String resourceId, String resourceType,
                                   String resourceName, String region) {
    }

    /**
     * The identity lookup's answer.
     *
     * @param identities one entry per requested id, in request order
     * @param requested  how many ids were asked about
     * @param resolved   how many matched an inventory row, so a client can tell an inventory that
     *                   has never run from one that simply does not hold this resource
     */
    public record ResourceIdentities(List<ResourceIdentity> identities, int requested,
                                     int resolved) {
    }
}
