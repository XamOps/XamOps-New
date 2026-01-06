package com.xammer.cloud.service.calculator.aws.config;

import com.xammer.cloud.domain.calculator.FormField;
import com.xammer.cloud.domain.calculator.ServiceConfig;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Database category configuration service.
 * Defines form configurations for RDS, DynamoDB, ElastiCache, Redshift, etc.
 */
@Service
public class DatabaseConfigService {

    private final Map<String, ServiceConfig> databaseConfigs = new HashMap<>();

    public DatabaseConfigService() {
        initializeConfigs();
    }

    private void initializeConfigs() {
        // RDS Configuration
        databaseConfigs.put("AmazonRDS", ServiceConfig.builder()
                .serviceCode("AmazonRDS")
                .serviceName("Amazon Relational Database Service (RDS)")
                .category("database")
                .fields(Arrays.asList(
                        FormField.select("engine", "Database Engine", true)
                                .options(Arrays.asList(
                                        "MySQL",
                                        "PostgreSQL",
                                        "MariaDB",
                                        "Oracle",
                                        "SQL Server",
                                        "Aurora MySQL",
                                        "Aurora PostgreSQL"
                                ))
                                .defaultValue("MySQL"),
                        FormField.selectDynamic("instanceClass", "Instance Class", true)
                                .apiEndpoint("/api/aws/calculator/rds/instance-types")
                                .dependsOn("engine")
                                .help("DB instance type (e.g., db.t3.medium)"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(100).min(20)
                                .help("Allocated storage in GB"),
                        FormField.select("storageType", "Storage Type", true)
                                .options(Arrays.asList("General Purpose SSD (gp3)", "General Purpose SSD (gp2)", "Provisioned IOPS (io1)", "Magnetic"))
                                .defaultValue("General Purpose SSD (gp3)"),
                        FormField.checkbox("multiAZ", "Multi-AZ Deployment")
                                .defaultValue(false)
                                .help("Enable high availability across multiple availability zones"),
                        FormField.number("backupStorageGB", "Backup Storage (GB)", false)
                                .defaultValue(0).min(0)
                                .help("Additional backup storage beyond free tier")
                ))
                .pricingEndpoint("/api/aws/calculator/rds/comprehensive-estimate")
                .build());

        // DynamoDB Configuration
        databaseConfigs.put("AmazonDynamoDB", ServiceConfig.builder()
                .serviceCode("AmazonDynamoDB")
                .serviceName("Amazon DynamoDB")
                .category("database")
                .fields(Arrays.asList(
                        FormField.select("capacityMode", "Capacity Mode", true)
                                .options(Arrays.asList("On-Demand", "Provisioned"))
                                .defaultValue("On-Demand")
                                .help("On-Demand: Pay per request, Provisioned: Reserve capacity"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(10).min(0)
                                .help("Total data storage in GB"),
                        FormField.number("readRequests", "Read Requests (millions/month)", false)
                                .defaultValue(1).min(0)
                                .help("Eventually consistent read requests"),
                        FormField.number("writeRequests", "Write Requests (millions/month)", false)
                                .defaultValue(1).min(0)
                                .help("Standard write requests"),
                        FormField.number("provisionedReadCapacity", "Provisioned Read Capacity Units", false)
                                .defaultValue(0).min(0)
                                .help("Only for Provisioned mode"),
                        FormField.number("provisionedWriteCapacity", "Provisioned Write Capacity Units", false)
                                .defaultValue(0).min(0)
                                .help("Only for Provisioned mode"),
                        FormField.checkbox("enableBackup", "Continuous Backups (PITR)")
                                .defaultValue(false)
                                .help("Point-in-time recovery")
                ))
                .pricingEndpoint("/api/aws/calculator/dynamodb/comprehensive-estimate")
                .build());

        // ElastiCache Configuration
        databaseConfigs.put("AmazonElastiCache", ServiceConfig.builder()
                .serviceCode("AmazonElastiCache")
                .serviceName("Amazon ElastiCache")
                .category("database")
                .fields(Arrays.asList(
                        FormField.select("engine", "Cache Engine", true)
                                .options(Arrays.asList("Redis", "Memcached"))
                                .defaultValue("Redis"),
                        FormField.selectDynamic("nodeType", "Node Type", true)
                                .apiEndpoint("/api/aws/calculator/elasticache/node-types")
                                .dependsOn("engine")
                                .help("Cache node instance type"),
                        FormField.number("numberOfNodes", "Number of Nodes", true)
                                .defaultValue(2).min(1)
                                .help("Number of cache nodes in the cluster"),
                        FormField.number("snapshotStorageGB", "Snapshot Storage (GB)", false)
                                .defaultValue(0).min(0)
                                .help("Backup snapshot storage (Redis only)"),
                        FormField.number("dataTransferOutGB", "Data Transfer Out (GB/month)", false)
                                .defaultValue(0).min(0)
                ))
                .pricingEndpoint("/api/aws/calculator/elasticache/comprehensive-estimate")
                .build());

        // Redshift Configuration
        databaseConfigs.put("AmazonRedshift", ServiceConfig.builder()
                .serviceCode("AmazonRedshift")
                .serviceName("Amazon Redshift")
                .category("database")
                .fields(Arrays.asList(
                        FormField.select("nodeType", "Node Type", true)
                                .options(Arrays.asList(
                                        "dc2.large",
                                        "dc2.8xlarge",
                                        "ra3.xlplus",
                                        "ra3.4xlarge",
                                        "ra3.16xlarge"
                                ))
                                .defaultValue("ra3.xlplus")
                                .help("Data warehouse node type"),
                        FormField.number("numberOfNodes", "Number of Nodes", true)
                                .defaultValue(2).min(1)
                                .help("Cluster size"),
                        FormField.number("managedStorageGB", "Managed Storage (GB)", false)
                                .defaultValue(0).min(0)
                                .help("For RA3 node types only"),
                        FormField.select("pricingModel", "Pricing Model", true)
                                .options(Arrays.asList("On-Demand", "Reserved (1-year)", "Reserved (3-year)"))
                                .defaultValue("On-Demand")
                ))
                .pricingEndpoint("/api/aws/calculator/redshift/comprehensive-estimate")
                .build());

        // Aurora Serverless Configuration
        databaseConfigs.put("AmazonAuroraServerless", ServiceConfig.builder()
                .serviceCode("AmazonAuroraServerless")
                .serviceName("Amazon Aurora Serverless")
                .category("database")
                .fields(Arrays.asList(
                        FormField.select("engine", "Engine", true)
                                .options(Arrays.asList("Aurora MySQL", "Aurora PostgreSQL"))
                                .defaultValue("Aurora MySQL"),
                        FormField.number("acuHoursPerMonth", "ACU Hours per Month", true)
                                .defaultValue(730).min(0)
                                .help("Aurora Capacity Units consumed per month"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(100).min(0)
                                .help("Database storage in GB"),
                        FormField.number("backupStorageGB", "Backup Storage (GB)", false)
                                .defaultValue(0).min(0)
                                .help("Backup storage beyond free tier"),
                        FormField.number("ioRequests", "I/O Requests (millions)", false)
                                .defaultValue(1).min(0)
                                .help("Database I/O requests")
                ))
                .pricingEndpoint("/api/aws/calculator/aurora-serverless/comprehensive-estimate")
                .build());

        // DocumentDB Configuration
        databaseConfigs.put("AmazonDocDB", ServiceConfig.builder()
                .serviceCode("AmazonDocDB")
                .serviceName("Amazon DocumentDB (MongoDB compatible)")
                .category("database")
                .fields(Arrays.asList(
                        FormField.selectDynamic("instanceClass", "Instance Class", true)
                                .apiEndpoint("/api/aws/calculator/docdb/instance-types")
                                .help("DocumentDB instance type"),
                        FormField.number("numberOfInstances", "Number of Instances", true)
                                .defaultValue(3).min(1)
                                .help("Cluster instances (min 3 for production)"),
                        FormField.number("storageGB", "Storage (GB)", true)
                                .defaultValue(100).min(10),
                        FormField.number("backupStorageGB", "Backup Storage (GB)", false)
                                .defaultValue(0).min(0),
                        FormField.number("ioRequests", "I/O Requests (millions)", true)
                                .defaultValue(1).min(0)
                ))
                .pricingEndpoint("/api/aws/calculator/docdb/comprehensive-estimate")
                .build());
    }

    public ServiceConfig getServiceConfig(String serviceCode) {
        return databaseConfigs.get(serviceCode);
    }

    public Map<String, ServiceConfig> getAllDatabaseConfigs() {
        return new HashMap<>(databaseConfigs);
    }
}
