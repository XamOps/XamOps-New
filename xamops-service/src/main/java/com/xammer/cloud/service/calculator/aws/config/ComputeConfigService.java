package com.xammer.cloud.service.calculator.aws.config;

import com.xammer.cloud.domain.calculator.FormField;
import com.xammer.cloud.domain.calculator.ServiceConfig;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Service
public class ComputeConfigService {

    private final Map<String, ServiceConfig> computeConfigs = new HashMap<>();

    public ComputeConfigService() {
        initializeConfigs();
    }

    private void initializeConfigs() {
        // EC2
        computeConfigs.put("AmazonEC2", ServiceConfig.builder()
                .serviceCode("AmazonEC2").serviceName("Amazon EC2").category("compute")
                .fields(Arrays.asList(
                        FormField.select("os", "Operating System", true).options(Arrays.asList("Linux", "Windows")),
                        FormField.selectDynamic("instanceType", "Instance Type", true)
                                .apiEndpoint("/api/aws/calculator/ec2/instance-types").dependsOn("os"),
                        FormField.number("instanceCount", "Instances", true).defaultValue(1),
                        FormField.number("hoursPerMonth", "Hours", true).defaultValue(730),
                        FormField.number("storageGB", "Storage (GB)", true).defaultValue(30),
                        FormField.select("storageType", "Type", true).options(Arrays.asList("gp3", "gp2")).defaultValue("gp3"),
                        FormField.number("dataTransferOutGB", "Data Transfer (GB)", false).defaultValue(0)
                ))
                .pricingEndpoint("/api/aws/calculator/ec2/comprehensive-estimate").build());

        // EKS
        computeConfigs.put("AmazonEKS", ServiceConfig.builder()
                .serviceCode("AmazonEKS").serviceName("Amazon EKS").category("compute")
                .fields(Arrays.asList(
                        FormField.number("clusters", "Clusters", true).defaultValue(1),
                        FormField.select("supportTier", "Support", true).options(Arrays.asList("Standard Support", "Extended Support")),
                        FormField.select("computeType", "Node Type", true).options(Arrays.asList("EC2 Instances", "Fargate (Serverless)")),
                        FormField.selectDynamic("instanceType", "EC2 Type", false)
                                .apiEndpoint("/api/aws/calculator/ec2/instance-types").dependsOn("region"),
                        FormField.number("nodeCount", "Nodes", false).defaultValue(2),
                        FormField.number("avgPodCount", "Pods (Fargate)", false).defaultValue(10),
                        FormField.number("vcpuPerPod", "vCPU/Pod", false).defaultValue(0.25),
                        FormField.number("memoryPerPod", "RAM/Pod (GB)", false).defaultValue(0.5),
                        FormField.number("storageGB", "Storage/Node (GB)", false).defaultValue(20)
                ))
                .pricingEndpoint("/api/aws/calculator/eks/comprehensive-estimate").build());

        // Lambda
        computeConfigs.put("AWSLambda", ServiceConfig.builder()
                .serviceCode("AWSLambda").serviceName("AWS Lambda").category("compute")
                .fields(Arrays.asList(
                        FormField.select("architecture", "Arch", true).options(Arrays.asList("x86", "arm64")),
                        FormField.number("memoryMB", "Memory (MB)", true).defaultValue(128),
                        FormField.number("requestsPerMonth", "Requests", true).defaultValue(1000000),
                        FormField.number("avgDurationMs", "Duration (ms)", true).defaultValue(100),
                        FormField.number("ephemeralStorageMB", "Ephemeral (MB)", false).defaultValue(512)
                ))
                .pricingEndpoint("/api/aws/calculator/lambda/comprehensive-estimate").build());
    }

    public ServiceConfig getServiceConfig(String code) { return computeConfigs.get(code); }
    public Map<String, ServiceConfig> getAllComputeConfigs() { return new HashMap<>(computeConfigs); }
}