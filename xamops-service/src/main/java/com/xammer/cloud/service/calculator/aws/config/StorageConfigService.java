package com.xammer.cloud.service.calculator.aws.config;

import com.xammer.cloud.domain.calculator.FormField;
import com.xammer.cloud.domain.calculator.ServiceConfig;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Storage category configuration service.
 * Defines form configurations for S3, EBS, EFS, Glacier, etc.
 */
@Service
public class StorageConfigService {

    private final Map<String, ServiceConfig> storageConfigs = new HashMap<>();

    public StorageConfigService() {
        initializeConfigs();
    }

    private void initializeConfigs() {
        // S3 Configuration
        storageConfigs.put("AmazonS3", ServiceConfig.builder()
                .serviceCode("AmazonS3")
                .serviceName("Amazon Simple Storage Service (S3)")
                .category("storage")
                .fields(Arrays.asList(
                        FormField.select("storageClass", "Storage Class", true)
                                .options(Arrays.asList(
                                        "Standard",
                                        "Intelligent-Tiering",
                                        "Standard-IA",
                                        "One Zone-IA",
                                        "Glacier Instant Retrieval",
                                        "Glacier Flexible Retrieval",
                                        "Glacier Deep Archive"
                                ))
                                .defaultValue("Standard"),
                        FormField.number("storageGB", "Storage Amount (GB)", true)
                                .defaultValue(100).min(0).step(0.01)
                                .help("Total storage in GB per month"),
                        FormField.number("putRequests", "PUT/POST Requests (per month)", false)
                                .defaultValue(10000).min(0)
                                .help("Number of PUT, COPY, POST, or LIST requests"),
                        FormField.number("getRequests", "GET Requests (per month)", false)
                                .defaultValue(100000).min(0)
                                .help("Number of GET and SELECT requests"),
                        FormField.number("dataTransferOutGB", "Data Transfer Out (GB)", false)
                                .defaultValue(10).min(0)
                                .help("Data transferred out to the internet")
                ))
                .pricingEndpoint("/api/aws/calculator/s3/comprehensive-estimate")
                .build());

        // EBS Configuration
        storageConfigs.put("AmazonEBS", ServiceConfig.builder()
                .serviceCode("AmazonEBS")
                .serviceName("Amazon Elastic Block Store (EBS)")
                .category("storage")
                .fields(Arrays.asList(
                        FormField.select("volumeType", "Volume Type", true)
                                .options(Arrays.asList(
                                        "gp3 (General Purpose SSD)",
                                        "gp2 (General Purpose SSD)",
                                        "io2 (Provisioned IOPS SSD)",
                                        "io1 (Provisioned IOPS SSD)",
                                        "st1 (Throughput Optimized HDD)",
                                        "sc1 (Cold HDD)"
                                ))
                                .defaultValue("gp3 (General Purpose SSD)"),
                        FormField.number("storageGB", "Volume Size (GB)", true)
                                .defaultValue(100).min(1)
                                .help("EBS volume size in GB"),
                        FormField.number("iops", "Provisioned IOPS", false)
                                .defaultValue(3000).min(0)
                                .help("Only for io1/io2 volumes"),
                        FormField.number("throughputMBps", "Throughput (MB/s)", false)
                                .defaultValue(125).min(0)
                                .help("Only for gp3 volumes"),
                        FormField.number("snapshotGB", "Snapshot Storage (GB)", false)
                                .defaultValue(0).min(0)
                                .help("EBS snapshots stored in S3")
                ))
                .pricingEndpoint("/api/aws/calculator/ebs/comprehensive-estimate")
                .build());

        // EFS Configuration
        storageConfigs.put("AmazonEFS", ServiceConfig.builder()
                .serviceCode("AmazonEFS")
                .serviceName("Amazon Elastic File System (EFS)")
                .category("storage")
                .fields(Arrays.asList(
                        FormField.select("storageClass", "Storage Class", true)
                                .options(Arrays.asList(
                                        "Standard",
                                        "Infrequent Access (IA)",
                                        "One Zone",
                                        "One Zone-IA"
                                ))
                                .defaultValue("Standard"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(100).min(0)
                                .help("Average storage per month"),
                        FormField.select("throughputMode", "Throughput Mode", true)
                                .options(Arrays.asList("Bursting", "Provisioned", "Elastic"))
                                .defaultValue("Bursting"),
                        FormField.number("provisionedThroughputMBps", "Provisioned Throughput (MB/s)", false)
                                .defaultValue(0).min(0)
                                .help("Only for Provisioned mode")
                ))
                .pricingEndpoint("/api/aws/calculator/efs/comprehensive-estimate")
                .build());

        // Glacier Configuration
        storageConfigs.put("AmazonGlacier", ServiceConfig.builder()
                .serviceCode("AmazonGlacier")
                .serviceName("Amazon S3 Glacier")
                .category("storage")
                .fields(Arrays.asList(
                        FormField.select("storageClass", "Glacier Class", true)
                                .options(Arrays.asList(
                                        "Glacier Instant Retrieval",
                                        "Glacier Flexible Retrieval",
                                        "Glacier Deep Archive"
                                ))
                                .defaultValue("Glacier Flexible Retrieval"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(1000).min(0)
                                .help("Average archive storage per month"),
                        FormField.number("retrievalRequests", "Retrieval Requests", false)
                                .defaultValue(0).min(0)
                                .help("Number of retrieval requests per month"),
                        FormField.number("retrievalGB", "Data Retrieved (GB)", false)
                                .defaultValue(0).min(0)
                                .help("Amount of data retrieved per month")
                ))
                .pricingEndpoint("/api/aws/calculator/glacier/comprehensive-estimate")
                .build());
    }

    public ServiceConfig getServiceConfig(String serviceCode) {
        return storageConfigs.get(serviceCode);
    }

    public Map<String, ServiceConfig> getAllStorageConfigs() {
        return new HashMap<>(storageConfigs);
    }
}
