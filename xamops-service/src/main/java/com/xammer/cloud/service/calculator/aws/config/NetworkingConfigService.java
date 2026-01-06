package com.xammer.cloud.service.calculator.aws.config;

import com.xammer.cloud.domain.calculator.FormField;
import com.xammer.cloud.domain.calculator.ServiceConfig;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Networking category configuration service.
 * Defines form configurations for VPC, ELB, CloudFront, Route 53, etc.
 */
@Service
public class NetworkingConfigService {

    private final Map<String, ServiceConfig> networkingConfigs = new HashMap<>();

    public NetworkingConfigService() {
        initializeConfigs();
    }

    private void initializeConfigs() {
        // VPC Configuration - COMPREHENSIVE
        networkingConfigs.put("AmazonVPC", ServiceConfig.builder()
                .serviceCode("AmazonVPC")
                .serviceName("Amazon Virtual Private Cloud (VPC)")
                .category("networking")
                .fields(Arrays.asList(
                        // Site-to-Site VPN
                        FormField.number("siteToSiteVpnConnections", "Site-to-Site VPN Connections", false)
                                .defaultValue(0).min(0)
                                .help("Number of VPN connections to your on-premises network"),
                        FormField.number("siteToSiteVpnHoursPerDay", "VPN Hours per Day", false)
                                .defaultValue(24).min(1).max(24)
                                .help("Average hours each VPN connection is active per day"),
                        
                        // Client VPN
                        FormField.number("clientVpnSubnets", "Client VPN Subnets", false)
                                .defaultValue(0).min(0)
                                .help("Number of subnets associated with Client VPN endpoint"),
                        FormField.number("clientVpnActiveConnections", "Client VPN Active Connections", false)
                                .defaultValue(0).min(0)
                                .help("Number of concurrent active VPN connections"),
                        FormField.number("clientVpnHoursPerDay", "Client VPN Hours per Day", false)
                                .defaultValue(10).min(1).max(24)
                                .help("Average hours each connection is active per day"),
                        FormField.number("clientVpnWorkingDaysPerMonth", "Working Days per Month", false)
                                .defaultValue(22).min(1).max(31)
                                .help("Number of working days users connect via VPN"),
                        
                        // NAT Gateway
                        FormField.number("natGatewayCount", "NAT Gateway Count", false)
                                .defaultValue(0).min(0)
                                .help("Number of NAT Gateways for private subnet internet access"),
                        FormField.number("natGatewayDataProcessedGB", "NAT Gateway Data Processed (GB/month)", false)
                                .defaultValue(0).min(0)
                                .help("Data processed through NAT Gateways"),
                        
                        // VPC Endpoints
                        FormField.number("interfaceEndpoints", "Interface Endpoints", false)
                                .defaultValue(0).min(0)
                                .help("Interface endpoints for AWS services (charged)"),
                        FormField.number("gatewayEndpoints", "Gateway Endpoints", false)
                                .defaultValue(0).min(0)
                                .help("Gateway endpoints for S3/DynamoDB (FREE)")
                ))
                .pricingEndpoint("/api/aws/calculator/vpc/comprehensive-estimate")
                .build());

        // Elastic Load Balancing - COMPREHENSIVE
        networkingConfigs.put("AWSELB", ServiceConfig.builder()
                .serviceCode("AWSELB")
                .serviceName("Elastic Load Balancing")
                .category("networking")
                .fields(Arrays.asList(
                        FormField.select("loadBalancerType", "Load Balancer Type", true)
                                .options(Arrays.asList(
                                        "Application Load Balancer (ALB)",
                                        "Network Load Balancer (NLB)",
                                        "Gateway Load Balancer (GWLB)",
                                        "Classic Load Balancer (CLB)"
                                ))
                                .defaultValue("Application Load Balancer (ALB)"),
                        FormField.number("numberOfLoadBalancers", "Number of Load Balancers", true)
                                .defaultValue(1).min(1),
                        FormField.number("processedBytesGB", "Processed Bytes (GB/hour)", false)
                                .defaultValue(10).min(0).step(0.01)
                                .help("Data processed by load balancer per hour"),
                        FormField.number("newConnectionsPerSecond", "New Connections per Second", false)
                                .defaultValue(25).min(0)
                                .help("New connections established per second"),
                        FormField.number("activeConnections", "Active Connections (avg)", false)
                                .defaultValue(3000).min(0)
                                .help("Average number of active connections"),
                        FormField.number("ruleEvaluations", "Rule Evaluations per Second", false)
                                .defaultValue(1000).min(0)
                                .help("Number of rules processed per second (ALB only)")
                ))
                .pricingEndpoint("/api/aws/calculator/elb/comprehensive-estimate")
                .build());

        // CloudFront Configuration
        networkingConfigs.put("AmazonCloudFront", ServiceConfig.builder()
                .serviceCode("AmazonCloudFront")
                .serviceName("Amazon CloudFront")
                .category("networking")
                .fields(Arrays.asList(
                        FormField.select("priceClass", "Price Class", true)
                                .options(Arrays.asList(
                                        "All Edge Locations (Best Performance)",
                                        "Use Only North America and Europe",
                                        "Use Only North America, Europe, Asia, Middle East, and Africa"
                                ))
                                .defaultValue("All Edge Locations (Best Performance)")
                                .help("Geographic distribution of edge locations"),
                        FormField.number("dataTransferOutGB", "Data Transfer Out (GB/month)", true)
                                .defaultValue(1000).min(0)
                                .help("Data transferred from CloudFront to users"),
                        FormField.number("httpRequests", "HTTP/HTTPS Requests (millions/month)", true)
                                .defaultValue(10).min(0)
                                .help("Total number of HTTP/HTTPS requests"),
                        FormField.number("invalidationRequests", "Invalidation Requests", false)
                                .defaultValue(0).min(0)
                                .help("First 1,000 paths are free per month")
                ))
                .pricingEndpoint("/api/aws/calculator/cloudfront/comprehensive-estimate")
                .build());

        // Route 53 Configuration
        networkingConfigs.put("AmazonRoute53", ServiceConfig.builder()
                .serviceCode("AmazonRoute53")
                .serviceName("Amazon Route 53")
                .category("networking")
                .fields(Arrays.asList(
                        FormField.number("hostedZones", "Hosted Zones", true)
                                .defaultValue(1).min(0)
                                .help("$0.50 per hosted zone per month"),
                        FormField.number("queriesPerMonth", "Queries (millions/month)", true)
                                .defaultValue(1).min(0)
                                .help("DNS queries answered per month"),
                        FormField.number("healthChecks", "Health Checks", false)
                                .defaultValue(0).min(0)
                                .help("AWS health checks for endpoints"),
                        FormField.select("trafficPolicy", "Traffic Policy", false)
                                .options(Arrays.asList("None", "Simple", "Advanced"))
                                .defaultValue("None")
                ))
                .pricingEndpoint("/api/aws/calculator/route53/comprehensive-estimate")
                .build());

        // Direct Connect Configuration
        networkingConfigs.put("AWSDirectConnect", ServiceConfig.builder()
                .serviceCode("AWSDirectConnect")
                .serviceName("AWS Direct Connect")
                .category("networking")
                .fields(Arrays.asList(
                        FormField.select("portSpeed", "Port Speed", true)
                                .options(Arrays.asList(
                                        "50 Mbps",
                                        "100 Mbps",
                                        "200 Mbps",
                                        "300 Mbps",
                                        "400 Mbps",
                                        "500 Mbps",
                                        "1 Gbps",
                                        "2 Gbps",
                                        "5 Gbps",
                                        "10 Gbps",
                                        "100 Gbps"
                                ))
                                .defaultValue("1 Gbps"),
                        FormField.number("dataTransferOutGB", "Data Transfer Out (GB/month)", true)
                                .defaultValue(1000).min(0)
                                .help("Data transferred from AWS to on-premises"),
                        FormField.select("connectionType", "Connection Type", true)
                                .options(Arrays.asList("Dedicated", "Hosted"))
                                .defaultValue("Dedicated")
                ))
                .pricingEndpoint("/api/aws/calculator/directconnect/comprehensive-estimate")
                .build());
    }

    public ServiceConfig getServiceConfig(String serviceCode) {
        return networkingConfigs.get(serviceCode);
    }

    public Map<String, ServiceConfig> getAllNetworkingConfigs() {
        return new HashMap<>(networkingConfigs);
    }
}
