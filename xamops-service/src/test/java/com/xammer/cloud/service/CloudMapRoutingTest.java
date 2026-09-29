package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Route;
import software.amazon.awssdk.services.ec2.model.RouteTable;
import software.amazon.awssdk.services.ec2.model.RouteTableAssociation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which subnets a route table governs, and therefore which servers sit behind a gateway.
 *
 * <p>Pinned by tests because the rule that matters most is the one AWS does not state: a subnet
 * with no explicit association is governed by the VPC's main table, silently. Reading only the
 * explicit associations is both the obvious implementation and wrong on most real VPCs, and the
 * symptom — a NAT gateway that appears to serve nothing — looks like missing data rather than a
 * missing rule.
 */
class CloudMapRoutingTest {

    private static final String VPC = "vpc-0abc";
    private static final String NAT = "nat-0a1b2c3d";
    private static final String IGW = "igw-0f9e8d7c";

    /** A route table with an explicit 0.0.0.0/0 target and explicit subnet associations. */
    private RouteTable table(String id, boolean main, String defaultTarget, String... subnetIds) {
        List<RouteTableAssociation> associations = new ArrayList<>();
        if (main) {
            // AWS reports the main association with no subnet id, which is the whole problem.
            associations.add(RouteTableAssociation.builder().main(true).build());
        }
        for (String subnetId : subnetIds) {
            associations.add(RouteTableAssociation.builder().main(false).subnetId(subnetId).build());
        }

        RouteTable.Builder builder = RouteTable.builder()
                .routeTableId(id)
                .associations(associations);
        if (defaultTarget == null) {
            return builder.routes(localRoute()).build();
        }
        Route.Builder route = Route.builder().destinationCidrBlock("0.0.0.0/0");
        if (defaultTarget.startsWith("igw-")) {
            route.gatewayId(defaultTarget);
        } else {
            route.natGatewayId(defaultTarget);
        }
        return builder.routes(localRoute(), route.build()).build();
    }

    /** Every table has one; it must never be mistaken for a default route. */
    private Route localRoute() {
        return Route.builder().destinationCidrBlock("10.0.0.0/16").gatewayId("local").build();
    }

    private CloudMapService.VpcRouting routingOf(RouteTable... tables) {
        CloudMapService.VpcRouting routing = new CloudMapService.VpcRouting();
        for (RouteTable table : tables) {
            routing.tables.add(table);
            for (RouteTableAssociation association : table.associations()) {
                if (Boolean.TRUE.equals(association.main())) {
                    routing.mainTable = table;
                }
                if (association.subnetId() != null) {
                    routing.bySubnet.put(association.subnetId(), table);
                }
            }
        }
        return routing;
    }

    private List<String> edgesOf(List<Map<String, Object>> elements, String label) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> element : elements) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) element.get("data");
            if (label.equals(data.get("label"))) {
                found.add(data.get("source") + "->" + data.get("target"));
            }
        }
        return found;
    }

    @Test
    @DisplayName("A subnet that names no route table is governed by the main one")
    void implicitAssociationsFallToTheMainTable() {
        RouteTable main = table("rtb-main", true, IGW);
        RouteTable priv = table("rtb-private", false, NAT, "subnet-private-a");
        CloudMapService.VpcRouting routing = routingOf(main, priv);

        // Before resolution the main table appears to govern nothing at all.
        assertTrue(routing.subnetsGovernedBy(main).isEmpty());

        routing.resolveImplicitAssociations(
                List.of("subnet-public-a", "subnet-public-b", "subnet-private-a"));

        assertEquals(List.of("subnet-public-a", "subnet-public-b"),
                routing.subnetsGovernedBy(main));
        // An explicit association is never overwritten by the main table.
        assertEquals(List.of("subnet-private-a"), routing.subnetsGovernedBy(priv));
    }

    @Test
    @DisplayName("A VPC with no main table resolves to nothing rather than guessing")
    void noMainTableLeavesSubnetsUngoverned() {
        RouteTable priv = table("rtb-private", false, NAT, "subnet-private-a");
        CloudMapService.VpcRouting routing = routingOf(priv);

        routing.resolveImplicitAssociations(List.of("subnet-private-a", "subnet-orphan"));

        assertEquals(List.of("subnet-private-a"), routing.subnetsGovernedBy(priv));
    }

    @Test
    @DisplayName("The gateway gets an edge to every subnet it is the exit for")
    void gatewayIsLinkedToTheSubnetsItServes() {
        RouteTable main = table("rtb-main", true, IGW);
        RouteTable priv = table("rtb-private", false, NAT, "subnet-private-a", "subnet-private-b");
        CloudMapService.VpcRouting routing = routingOf(main, priv);
        routing.resolveImplicitAssociations(
                List.of("subnet-public-a", "subnet-private-a", "subnet-private-b"));

        List<Map<String, Object>> elements = new ArrayList<>();
        new CloudMapService().addRouteTableNodes(elements, VPC, routing);

        // The answer to "which servers use this NAT gateway", one hop from the gateway itself.
        assertEquals(List.of(NAT + "->subnet-private-a", NAT + "->subnet-private-b"),
                edgesOf(elements, "egress for").stream().filter(e -> e.startsWith(NAT)).toList());
        // The same relationship holds for an internet gateway, including via the main table.
        assertTrue(edgesOf(elements, "egress for").contains(IGW + "->subnet-public-a"));

        // The routing story the tables themselves tell is unchanged.
        assertTrue(edgesOf(elements, "default via").contains("rtb-private->" + NAT));
        assertTrue(edgesOf(elements, "routes").contains("rtb-main->subnet-public-a"),
                "the main table's implicit subnets must be routed too");
    }

    @Test
    @DisplayName("An isolated table claims no gateway, so no egress edge is drawn")
    void aTableWithNoDefaultRouteHasNoGateway() {
        RouteTable isolated = table("rtb-isolated", false, null, "subnet-isolated-a");
        CloudMapService.VpcRouting routing = routingOf(isolated);

        List<Map<String, Object>> elements = new ArrayList<>();
        new CloudMapService().addRouteTableNodes(elements, VPC, routing);

        assertTrue(edgesOf(elements, "egress for").isEmpty());
        assertTrue(edgesOf(elements, "routes").contains("rtb-isolated->subnet-isolated-a"));
        assertEquals("Isolated", routing.tierOf("subnet-isolated-a"));
    }

    @Test
    @DisplayName("Public means the default route is an internet gateway, not merely that one exists")
    void tierDistinguishesInternetGatewayFromNat() {
        RouteTable main = table("rtb-main", true, IGW);
        RouteTable priv = table("rtb-private", false, NAT, "subnet-private-a");
        CloudMapService.VpcRouting routing = routingOf(main, priv);
        routing.resolveImplicitAssociations(List.of("subnet-public-a", "subnet-private-a"));

        assertEquals("Public", routing.tierOf("subnet-public-a"));
        assertEquals("Private", routing.tierOf("subnet-private-a"));
        assertFalse("Public".equals(routing.tierOf("subnet-private-a")));
    }
}
