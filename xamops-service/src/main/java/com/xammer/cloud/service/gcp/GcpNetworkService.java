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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public CompletableFuture<List<Map<String, Object>>> getNetworkTopologyGraph(String gcpProjectId) {
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
            
            routes.forEach(route -> {
                 Map<String, Object> routeEdge = new HashMap<>();
                 Map<String, Object> routeData = new HashMap<>();
                 routeData.put("id", route.getSelfLink());
                 routeData.put("source", route.getNetwork());
                 routeData.put("target", route.getNextHopInstance()); // Simplified, could be gateway, etc.
                 routeData.put("label", "ROUTE: " + route.getName());
                 routeEdge.put("data", routeData);
                 elements.add(routeEdge);
            });

            return elements;
        });
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