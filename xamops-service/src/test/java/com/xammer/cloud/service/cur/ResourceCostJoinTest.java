package com.xammer.cloud.service.cur;

import com.xammer.cloud.service.ResourceIdResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attribution, with no Athena, Spring or database in the way.
 *
 * <p>Every test here is a plain function call, which is the point of the class being pure: a cost
 * landing on the wrong resource is indistinguishable from a real one once it renders, so the
 * arithmetic deserves tests that are cheap enough to cover the odd cases.
 */
class ResourceCostJoinTest {

    @Test
    @DisplayName("A group is attributed to the requested id whose candidate set holds its id")
    void attributionFollowsTheCandidateSet() {
        ResourceIdResolver.CurIdentifiers candidates = identifiers(Map.of(
                "i-0abc", List.of("i-0abc"),
                "vol-0aa1", List.of("vol-0aa1")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("i-0abc", "vol-0aa1"),
                candidates,
                groups(2106.09, 2, group("i-0abc", 1621.35), group("vol-0aa1", 484.74)),
                CostBasis.UNBLENDED);

        assertEquals(2, response.pricedCount());
        assertEquals("i-0abc", response.costs().get(0).resourceId());
        assertEquals(1621.35, response.costs().get(0).cost(), 0.0001);
        assertEquals(484.74, response.costs().get(1).cost(), 0.0001);
        assertEquals("unblended", response.basis());
        assertTrue(response.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("Two candidate forms of one resource sum into one figure")
    void twoFormsOfOneResourceSum() {
        // Reachable and normal: AWS can write a bare id early in the month and the ARN later, so
        // one resource is two CUR groups. This is the case a LIMIT of MAX_LOOKUP_IDS would have
        // truncated.
        ResourceIdResolver.CurIdentifiers candidates = identifiers(Map.of(
                "xammer-prod", List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("xammer-prod"),
                candidates,
                groups(2540.40, 2,
                        group("xammer-prod", 1200.40),
                        group("arn:aws:rds:ap-south-1:605:db:xammer-prod", 1340.00)),
                CostBasis.UNBLENDED);

        assertEquals(1, response.costs().size());
        assertEquals(2540.40, response.costs().get(0).cost(), 0.0001);
        assertEquals(1, response.pricedCount());
    }

    @Test
    @DisplayName("An id that lost its colliding candidate still prices from its ARN, unmarked")
    void anIdKeepingOneCandidateIsNotMarked() {
        // The shared short id never entered the predicate, so no group can carry it. Each id is
        // still priced from its own surviving unique candidate.
        ResourceIdResolver.CurIdentifiers candidates = identifiers(Map.of(
                "arn:aws:rds:ap-south-1:605:db:shared", List.of("arn:aws:rds:ap-south-1:605:db:shared"),
                "arn:aws:eks:ap-south-1:605:cluster/shared",
                List.of("arn:aws:eks:ap-south-1:605:cluster/shared")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("arn:aws:rds:ap-south-1:605:db:shared",
                        "arn:aws:eks:ap-south-1:605:cluster/shared"),
                candidates,
                groups(3120.40, 2,
                        group("arn:aws:rds:ap-south-1:605:db:shared", 2540.40),
                        group("arn:aws:eks:ap-south-1:605:cluster/shared", 580.00)),
                CostBasis.UNBLENDED);

        assertEquals(2, response.pricedCount());
        assertTrue(response.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("Two requested ids resolving to one row are aliases and both show the figure")
    void aliasesBothPrice() {
        ResourceIdResolver.CurIdentifiers candidates = identifiers(Map.of(
                "xammer-prod", List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod"),
                "arn:aws:rds:ap-south-1:605:db:xammer-prod",
                List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod"),
                candidates,
                groups(2540.40, 1, group("arn:aws:rds:ap-south-1:605:db:xammer-prod", 2540.40)),
                CostBasis.UNBLENDED);

        // Both, with the same figure. A last-write-wins owners map would blank one of two names
        // for one resource. pricedCount is 2 and totalCost is the window's 2540.40, which the
        // caller's footnote already explains is the sum of what could be priced rather than a
        // slice of the bill.
        assertEquals(2, response.costs().size());
        assertEquals(2540.40, response.costs().get(0).cost(), 0.0001);
        assertEquals(2540.40, response.costs().get(1).cost(), 0.0001);
        assertEquals(2540.40, response.totalCost(), 0.0001);
    }

    @Test
    @DisplayName("Charges and credits that cancel across two forms yield no entry, not a $0.00")
    void aCancellationAcrossTwoFormsIsDropped() {
        /*
         * The query's own HAVING ABS(SUM(cost)) > 0.001 cannot see this: each group survives on its
         * own and only the sum here is zero. A present-and-zero entry would render as $0.00, the
         * one figure the connectivity tab must never show — and it is not ambiguous either, since
         * the question was answered.
         */
        ResourceIdResolver.CurIdentifiers candidates = identifiers(Map.of(
                "i-0abc", List.of("i-0abc", "arn:aws:ec2:ap-south-1:605:instance/i-0abc")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("i-0abc"),
                candidates,
                groups(0.0, 2,
                        group("i-0abc", 5.00),
                        group("arn:aws:ec2:ap-south-1:605:instance/i-0abc", -5.00)),
                CostBasis.UNBLENDED);

        assertTrue(response.costs().isEmpty());
        assertEquals(0, response.pricedCount());
        assertTrue(response.ambiguous().isEmpty());
    }

    @Test
    @DisplayName("A group nobody asked about is ignored rather than attributed anywhere")
    void anUnownedGroupIsIgnored() {
        ResourceIdResolver.CurIdentifiers candidates =
                identifiers(Map.of("i-0abc", List.of("i-0abc")));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("i-0abc"),
                candidates,
                groups(1621.35, 2, group("i-0abc", 1621.35), group("i-0zzz", 999.00)),
                CostBasis.UNBLENDED);

        assertEquals(1, response.costs().size());
        assertEquals(1621.35, response.costs().get(0).cost(), 0.0001);
    }

    @Test
    @DisplayName("An unanswerable id is reported in ambiguous with its requested count intact")
    void anUnanswerableIdIsReported() {
        ResourceIdResolver.CurIdentifiers candidates = new ResourceIdResolver.CurIdentifiers(
                Map.of("shared", List.of(), "i-0abc", List.of("i-0abc")),
                Map.of("shared", List.of("shared"), "i-0abc", List.of("i-0abc")),
                Map.of(),
                Set.of("shared"));

        ResourceCostJoin.ResourceCostsResponse response = ResourceCostJoin.join(
                List.of("shared", "i-0abc"),
                candidates,
                groups(1621.35, 1, group("i-0abc", 1621.35)),
                CostBasis.UNBLENDED);

        assertEquals(List.of("shared"), response.ambiguous());
        assertEquals(2, response.requestedCount());
        assertEquals(1, response.pricedCount());
    }

    // ── identities ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every requested id gets an identity entry, resolved or not")
    void identitiesCoverEveryRequestedId() {
        ResourceIdResolver.CurIdentifiers candidates = new ResourceIdResolver.CurIdentifiers(
                Map.of("xammer-prod", List.of("xammer-prod"), "igw-001", List.of("igw-001")),
                Map.of("xammer-prod", List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod"),
                        "igw-001", List.of("igw-001")),
                Map.of("xammer-prod", new ResourceIdResolver.CanonicalResource(
                        "xammer-prod", "RDS Instance", "xammer-prod", "ap-south-1")),
                Set.of());

        ResourceCostJoin.ResourceIdentities identities =
                ResourceCostJoin.identities(List.of("xammer-prod", "igw-001"), candidates);

        assertEquals(2, identities.requested());
        assertEquals(1, identities.resolved());
        // The locate list, not the pricing list: resource_id first, because that is the id the
        // inventory-plane graph path emits.
        assertEquals(List.of("xammer-prod", "arn:aws:rds:ap-south-1:605:db:xammer-prod"),
                identities.identities().get(0).nodeIdCandidates());
        assertEquals("RDS Instance", identities.identities().get(0).resourceType());
        // Present rather than dropped: absence would be indistinguishable from the server having
        // lost the id, and the client needs to tell those apart.
        assertEquals("igw-001", identities.identities().get(1).requestedId());
        assertNull(identities.identities().get(1).resourceId());
        assertEquals(List.of("igw-001"), identities.identities().get(1).nodeIdCandidates());
    }

    @Test
    @DisplayName("An unanswerable id still carries itself as a locate candidate")
    void anUnanswerableIdIsStillLocatable() {
        // Handing the empty pricing list over as nodeIdCandidates would render "not in the account
        // topology" — the one unavailable state with no retry path — for a resource that is in the
        // graph.
        ResourceIdResolver.CurIdentifiers candidates = new ResourceIdResolver.CurIdentifiers(
                Map.of("shared", List.of()),
                Map.of("shared", List.of("shared")),
                Map.of(),
                Set.of("shared"));

        ResourceCostJoin.ResourceIdentities identities =
                ResourceCostJoin.identities(List.of("shared"), candidates);

        assertEquals(List.of("shared"), identities.identities().get(0).nodeIdCandidates());
        assertEquals(0, identities.resolved());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static ResourceIdResolver.CurIdentifiers identifiers(Map<String, List<String>> pricing) {
        return new ResourceIdResolver.CurIdentifiers(pricing, pricing, Map.of(), Set.of());
    }

    private static CurAdvancedMetricsService.ResourceGroupCost group(String curId, double cost) {
        return new CurAdvancedMetricsService.ResourceGroupCost(curId, cost);
    }

    private static CurAdvancedMetricsService.ResourceGroupCosts groups(
            double totalCost, long groupCount, CurAdvancedMetricsService.ResourceGroupCost... groups) {
        return new CurAdvancedMetricsService.ResourceGroupCosts(List.of(groups), totalCost,
                groupCount);
    }
}
