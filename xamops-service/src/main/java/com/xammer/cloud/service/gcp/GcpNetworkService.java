package com.xammer.cloud.service.gcp;

import com.google.cloud.compute.v1.Firewall;
import com.google.cloud.compute.v1.FirewallsClient;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.Network;
import com.google.cloud.compute.v1.NetworksClient;
import com.google.cloud.compute.v1.Route;
import com.google.cloud.compute.v1.RoutesClient;
import com.google.cloud.compute.v1.Subnetwork;
import com.google.cloud.compute.v1.SubnetworksClient;
import com.xammer.cloud.dto.gcp.GcpVpcTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpNetworkService {

    private final GcpClientProvider gcpClientProvider;

    public GcpNetworkService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public CompletableFuture<GcpVpcTopology> getVpcTopology(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            GcpVpcTopology topology = new GcpVpcTopology();
            topology.setNetworks(getNetworks(gcpProjectId));
            topology.setSubnetworks(getSubnetworks(gcpProjectId));
            topology.setInstances(getInstances(gcpProjectId));
            return topology;
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getNetworkTopologyGraph(String gcpProjectId, String vpcId) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> elements = new ArrayList<>();
            List<Network> networks = getNetworks(gcpProjectId);
            List<Subnetwork> subnetworks = getSubnetworks(gcpProjectId);
            List<Instance> instances = getInstances(gcpProjectId);
            List<Firewall> firewalls = getFirewalls(gcpProjectId);
            List<Route> routes = getRoutes(gcpProjectId);

            networks.forEach(network -> {
                Map<String, Object> vpcNode = new HashMap<>();
                Map<String, Object> vpcData = new HashMap<>();
                vpcData.put("id", network.getSelfLink());
                vpcData.put("label", network.getName());
                vpcData.put("type", "VPC");
                vpcNode.put("data", vpcData);
                elements.add(vpcNode);
            });

            subnetworks.forEach(subnet -> {
                Map<String, Object> subnetNode = new HashMap<>();
                Map<String, Object> subnetData = new HashMap<>();
                subnetData.put("id", subnet.getSelfLink());
                subnetData.put("label", subnet.getName());
                subnetData.put("type", "Subnet");
                subnetData.put("parent", subnet.getNetwork());
                subnetNode.put("data", subnetData);
                elements.add(subnetNode);
            });

            // Note: This adds instances per network interface, which could cause duplicate node IDs
            // if an instance has multiple NICs. To fix, consider deduplicating (e.g., add once with parent as first subnet)
            // or use unique IDs per attachment (e.g., instanceId + "-ni-" + niName) to show in multiple parents.
            instances.forEach(instance -> {
                instance.getNetworkInterfacesList().forEach(ni -> {
                    Map<String, Object> instanceNode = new HashMap<>();
                    Map<String, Object> instanceData = new HashMap<>();
                    instanceData.put("id", instance.getSelfLink());
                    instanceData.put("label", instance.getName());
                    instanceData.put("type", "Instance");
                    instanceData.put("parent", ni.getSubnetwork());
                    instanceNode.put("data", instanceData);
                    elements.add(instanceNode);
                });
            });

            firewalls.forEach(firewall -> {
                Map<String, Object> firewallNode = new HashMap<>();
                Map<String, Object> firewallData = new HashMap<>();
                firewallData.put("id", firewall.getSelfLink());
                firewallData.put("label", firewall.getName());
                firewallData.put("type", "Firewall");
                firewallData.put("parent", firewall.getNetwork());
                firewallNode.put("data", firewallData);
                elements.add(firewallNode);
            });

            // Collect existing node IDs
            Set<String> existingNodeIds = elements.stream()
                    .filter(el -> el.get("data") != null)
                    .map(el -> (String) ((Map<?, ?>) el.get("data")).get("id"))
                    .collect(Collectors.toCollection(HashSet::new));

            routes.forEach(route -> {
                NextHopInfo nextHopInfo = getNextHopInfo(route);
                if (nextHopInfo != null) {
                    String target = nextHopInfo.target;
                    if (!existingNodeIds.contains(target)) {
                        Map<String, Object> placeholderNode = new HashMap<>();
                        Map<String, Object> placeholderData = new HashMap<>();
                        placeholderData.put("id", target);
                        placeholderData.put("label", extractLabel(target));
                        placeholderData.put("type", nextHopInfo.type);
                        placeholderData.put("parent", route.getNetwork());
                        placeholderNode.put("data", placeholderData);
                        elements.add(placeholderNode);
                        existingNodeIds.add(target);
                    }

                    Map<String, Object> routeEdge = new HashMap<>();
                    Map<String, Object> routeData = new HashMap<>();
                    routeData.put("id", route.getSelfLink());
                    routeData.put("source", route.getNetwork());
                    routeData.put("target", target);
                    routeData.put("label", "ROUTE: " + route.getName());
                    routeEdge.put("data", routeData);
                    elements.add(routeEdge);
                } else {
                    log.warn("Skipping route without identifiable next hop: {}", route.getName());
                }
            });

            return elements;
        });
    }

    private NextHopInfo getNextHopInfo(Route route) {
        if (route.hasNextHopGateway()) {
            return new NextHopInfo(route.getNextHopGateway(), "Gateway");
        }
        if (route.hasNextHopHub()) {
            return new NextHopInfo(route.getNextHopHub(), "Hub");
        }
        if (route.hasNextHopIlb()) {
            return new NextHopInfo(route.getNextHopIlb(), "ILB");
        }
        if (route.hasNextHopInstance()) {
            return new NextHopInfo(route.getNextHopInstance(), "Instance");
        }
        if (route.hasNextHopNetwork()) {
            return new NextHopInfo(route.getNextHopNetwork(), "Network");
        }
        if (route.hasNextHopPeering()) {
            return new NextHopInfo(route.getNextHopPeering(), "Peering");
        }
        if (route.hasNextHopVpnTunnel()) {
            return new NextHopInfo(route.getNextHopVpnTunnel(), "VPN Tunnel");
        }
        if (route.hasNextHopIp()) {
            return new NextHopInfo("ip:" + route.getNextHopIp(), "IP");
        }
        return null;
    }

    private String extractLabel(String link) {
        if (link.startsWith("ip:")) {
            return link.substring(3);
        }
        String[] parts = link.split("/");
        return parts[parts.length - 1];
    }

    private static class NextHopInfo {
        String target;
        String type;

        NextHopInfo(String target, String type) {
            this.target = target;
            this.type = type;
        }
    }

    private List<Network> getNetworks(String gcpProjectId) {
        Optional<NetworksClient> clientOpt = gcpClientProvider.getNetworksClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (NetworksClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .collect(Collectors.toList());
        }
    }

    private List<Subnetwork> getSubnetworks(String gcpProjectId) {
        Optional<SubnetworksClient> clientOpt = gcpClientProvider.getSubnetworksClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (SubnetworksClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getSubnetworksList().stream())
                    .collect(Collectors.toList());
        }
    }

    private List<Instance> getInstances(String gcpProjectId) {
        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (InstancesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getInstancesList().stream())
                    .collect(Collectors.toList());
        }
    }

    private List<Firewall> getFirewalls(String gcpProjectId) {
        Optional<FirewallsClient> clientOpt = gcpClientProvider.getFirewallsClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (FirewallsClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .collect(Collectors.toList());
        }
    }

    private List<Route> getRoutes(String gcpProjectId) {
        Optional<RoutesClient> clientOpt = gcpClientProvider.getRoutesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (RoutesClient client = clientOpt.get()) {
            return StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .collect(Collectors.toList());
        }
    }
}