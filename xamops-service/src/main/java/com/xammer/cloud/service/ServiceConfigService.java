package com.xammer.cloud.service;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Central registry of AWS service configs for the calculator.
 * This version uses dynamic field loading and standardized pricing endpoints.
 */
@Service
public class ServiceConfigService {

    private final Map<String, ServiceConfig> serviceConfigs = new HashMap<>();

    public ServiceConfigService() {
        initializeConfigs();
    }

    private void initializeConfigs() {
        // =====================================================
        // EC2 Configuration - FULLY DYNAMIC & FIXED
        // =====================================================
        serviceConfigs.put("AmazonEC2", ServiceConfig.builder()
                .serviceCode("AmazonEC2")
                .serviceName("Amazon Elastic Compute Cloud (EC2)")
                .category("compute")
                .fields(Arrays.asList(
                        // Region field (dynamic - loads from backend)
                        FormField.selectDynamic("region", "AWS Region", true)
                                .apiEndpoint("/api/aws/calculator/regions")
                                .defaultValue("ap-south-1"),
                        
                        // OS selection
                        FormField.select("os", "Operating System", true)
                                .options(Arrays.asList("Linux", "Windows", "RHEL", "SUSE"))
                                .defaultValue("Linux"),
                        
                        // Instance Type (depends on region + OS)
                        FormField.selectDynamic("instanceType", "Instance Type", true)
                                .apiEndpoint("/api/aws/calculator/ec2/instance-types")
                                .dependsOn("region,os")
                                .help("Dynamic list based on region & OS selection"),
                        
                        FormField.number("instanceCount", "Instance Count", true)
                                .defaultValue(1).min(1),
                        
                        FormField.number("hours", "Hours per Month", true)
                                .defaultValue(730).min(1).max(744)
                                .help("730 hours = average month, 744 = full 31-day month"),
                        
                        FormField.number("storageGB", "EBS Storage (GB)", false)
                                .defaultValue(30).min(0)
                                .help("Root volume + additional EBS volumes")
                ))
                .pricingEndpoint("/api/aws/calculator/ec2/comprehensive-estimate")
                .build()
        );

        // =====================================================
        // RDS Configuration - FIXED with proper dependencies
        // =====================================================
        serviceConfigs.put("AmazonRDS", ServiceConfig.builder()
                .serviceCode("AmazonRDS")
                .serviceName("Amazon Relational Database Service (RDS)")
                .category("database")
                .fields(Arrays.asList(
                        FormField.selectDynamic("region", "AWS Region", true)
                                .apiEndpoint("/api/aws/calculator/regions")
                                .defaultValue("ap-south-1"),
                        
                        FormField.select("engine", "Database Engine", true)
                                .options(Arrays.asList("MySQL", "PostgreSQL", "MariaDB", 
                                                     "Oracle", "SQL Server", 
                                                     "Aurora MySQL", "Aurora PostgreSQL"))
                                .defaultValue("MySQL"),
                        
                        FormField.selectDynamic("instanceClass", "Instance Class", true)
                                .apiEndpoint("/api/aws/calculator/rds/instance-types")
                                .dependsOn("region")
                                .help("Dynamic list based on region availability"),
                        
                        FormField.number("storageGB", "Allocated Storage (GB)", true)
                                .defaultValue(100).min(20).step(1)
                                .help("General Purpose SSD (gp3) pricing used"),
                        
                        FormField.checkbox("multiAZ", "Multi-AZ Deployment")
                                .defaultValue(false)
                                .help("High availability across 2 AZs (higher cost)"),
                        
                        FormField.number("backupRetentionDays", "Backup Retention (days)", false)
                                .defaultValue(7).min(0).max(35)
                                .help("Automated backups (charged after free tier)")
                ))
                .pricingEndpoint("/api/aws/calculator/rds/comprehensive-estimate")
                .build()
        );

        // =====================================================
        // S3 Configuration
        // =====================================================
        serviceConfigs.put("AmazonS3", ServiceConfig.builder()
                .serviceCode("AmazonS3")
                .serviceName("Amazon Simple Storage Service (S3)")
                .category("storage")
                .fields(Arrays.asList(
                        FormField.selectDynamic("region", "AWS Region", true)
                                .apiEndpoint("/api/aws/calculator/regions")
                                .defaultValue("ap-south-1"),
                        
                        FormField.select("storageClass", "S3 Storage class", true)
                                .options(Arrays.asList(
                                        "S3 Standard",
                                        "S3 Standard-IA",
                                        "S3 One Zone-IA",
                                        "S3 Intelligent-Tiering",
                                        "S3 Glacier Instant Retrieval",
                                        "S3 Glacier Flexible Retrieval",
                                        "S3 Glacier Deep Archive"
                                )).defaultValue("S3 Standard"),

                        FormField.number("storageGB", "S3 storage amount (GB)", true)
                                .defaultValue(100).min(0).step(0.01)
                                .help("Average amount of data stored per month."),

                        FormField.number("putRequests", "PUT/POST/LIST requests per month", false)
                                .defaultValue(100000).min(0)
                                .help("Charged per 1,000 requests."),

                        FormField.number("getRequests", "GET and other requests per month", false)
                                .defaultValue(100000).min(0)
                                .help("Charged per 1,000 requests."),

                        FormField.number("dataTransferOutGB", "Data transfer out (GB/month)", false)
                                .defaultValue(100).min(0)
                                .help("Data transferred out to the internet or other regions.")
                ))
                .pricingEndpoint("/api/aws/calculator/s3/comprehensive-estimate")
                .build()
        );

        // =====================================================
        // Lambda Configuration - ENHANCED
        // =====================================================
        serviceConfigs.put("AWSLambda", ServiceConfig.builder()
                .serviceCode("AWSLambda")
                .serviceName("AWS Lambda")
                .category("compute")
                .fields(Arrays.asList(
                        FormField.selectDynamic("region", "AWS Region", true)
                                .apiEndpoint("/api/aws/calculator/regions")
                                .defaultValue("ap-south-1"),
                        
                        FormField.select("architecture", "Architecture", true)
                                .options(Arrays.asList("x86", "arm64"))
                                .defaultValue("x86"),
                        
                        FormField.number("memoryGB", "Memory (GB)", true)
                                .defaultValue(1).min(0.125).max(10.24).step(0.125)
                                .help("Memory allocation determines vCPU (1GB = ~1 vCPU)"),
                        
                        FormField.number("requestsPerMonth", "Requests per month (millions)", true)
                                .defaultValue(1).min(0).step(0.001),
                        
                        FormField.number("durationSeconds", "Avg duration (seconds)", true)
                                .defaultValue(0.5).min(0.00001).max(900)
                                .help("Average execution time per request")
                ))
                .pricingEndpoint("/api/aws/calculator/lambda/comprehensive-estimate")
                .build()
        );

        // =====================================================
        // VPC Configuration
        // =====================================================
        serviceConfigs.put("AmazonVPC", ServiceConfig.builder()
                .serviceCode("AmazonVPC")
                .serviceName("Amazon Virtual Private Cloud (VPC)")
                .category("network")
                .fields(Arrays.asList(
                        FormField.selectDynamic("region", "AWS Region", true)
                                .apiEndpoint("/api/aws/calculator/regions")
                                .defaultValue("ap-south-1"),
                        FormField.number("natGatewayCount", "NAT Gateway Count", false)
                                .defaultValue(0).min(0),
                        FormField.number("natGatewayDataProcessedGB", "NAT Data Processed (GB/month)", false)
                                .defaultValue(0).min(0)
                ))
                .pricingEndpoint("/api/aws/calculator/vpc/comprehensive-estimate")
                .build()
        );

        // =====================================================
        // Default configuration for unknown services
        // =====================================================
        serviceConfigs.put("_default", ServiceConfig.builder()
                .serviceCode("_default")
                .serviceName("Generic AWS Service")
                .category("generic")
                .fields(Arrays.asList(
                        FormField.number("quantity", "Quantity", true)
                                .defaultValue(1).min(1),
                        FormField.number("hours", "Hours per month", true)
                                .defaultValue(730).min(1),
                        FormField.number("unitPrice", "Unit Price (USD/hr)", true)
                                .defaultValue(0.10).min(0).step(0.0001)
                ))
                .pricingEndpoint(null)
                .build()
        );
    }

    public ServiceConfig getServiceConfig(String serviceCode) {
        return serviceConfigs.getOrDefault(serviceCode, serviceConfigs.get("_default"));
    }

    public Map<String, ServiceConfig> getAllServiceConfigs() {
        return new HashMap<>(serviceConfigs);
    }

    // =========================================================
    // Configuration Domain Classes
    // =========================================================
    public static class ServiceConfig {
        private String serviceCode;
        private String serviceName;
        private String category;
        private List<FormField> fields;
        private String pricingEndpoint;

        public static ServiceConfigBuilder builder() { return new ServiceConfigBuilder(); }

        public String getServiceCode() { return serviceCode; }
        public String getServiceName() { return serviceName; }
        public String getCategory() { return category; }
        public List<FormField> getFields() { return fields; }
        public String getPricingEndpoint() { return pricingEndpoint; }

        public static class ServiceConfigBuilder {
            private String serviceCode;
            private String serviceName;
            private String category;
            private List<FormField> fields;
            private String pricingEndpoint;

            public ServiceConfigBuilder serviceCode(String sc) { this.serviceCode = sc; return this; }
            public ServiceConfigBuilder serviceName(String sn) { this.serviceName = sn; return this; }
            public ServiceConfigBuilder category(String c) { this.category = c; return this; }
            public ServiceConfigBuilder fields(List<FormField> f) { this.fields = f; return this; }
            public ServiceConfigBuilder pricingEndpoint(String pe) { this.pricingEndpoint = pe; return this; }
            public ServiceConfig build() {
                ServiceConfig c = new ServiceConfig();
                c.serviceCode = this.serviceCode;
                c.serviceName = this.serviceName;
                c.category = this.category;
                c.fields = this.fields;
                c.pricingEndpoint = this.pricingEndpoint;
                return c;
            }
        }
    }

    public static class FormField {
        private String name;
        private String type;
        private String label;
        private boolean required;
        private Object defaultValue;
        private List<String> options;
        private String apiEndpoint;
        private String dependsOn;
        private Number min;
        private Number max;
        private Number step;
        private String help;

        public static FormField select(String n, String l, boolean r) {
            FormField f = new FormField(); f.name = n; f.label = l; f.type = "select"; f.required = r; return f;
        }
        public static FormField selectDynamic(String n, String l, boolean r) {
            FormField f = new FormField(); f.name = n; f.label = l; f.type = "select-dynamic"; f.required = r; return f;
        }
        public static FormField number(String n, String l, boolean r) {
            FormField f = new FormField(); f.name = n; f.label = l; f.type = "number"; f.required = r; return f;
        }
        public static FormField checkbox(String n, String l) {
            FormField f = new FormField(); f.name = n; f.label = l; f.type = "checkbox"; return f;
        }

        public FormField options(List<String> o) { this.options = o; return this; }
        public FormField apiEndpoint(String e) { this.apiEndpoint = e; return this; }
        public FormField dependsOn(String d) { this.dependsOn = d; return this; }
        public FormField defaultValue(Object v) { this.defaultValue = v; return this; }
        public FormField min(Number m) { this.min = m; return this; }
        public FormField max(Number m) { this.max = m; return this; }
        public FormField step(Number s) { this.step = s; return this; }
        public FormField help(String h) { this.help = h; return this; }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getLabel() { return label; }
        public boolean isRequired() { return required; }
        public Object getDefaultValue() { return defaultValue; }
        public List<String> getOptions() { return options; }
        public String getApiEndpoint() { return apiEndpoint; }
        public String getDependsOn() { return dependsOn; }
        public Number getMin() { return min; }
        public Number getMax() { return max; }
        public Number getStep() { return step; }
        public String getHelp() { return help; }
    }
}