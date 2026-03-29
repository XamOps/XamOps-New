package com.xammer.cloud.service.marketplace;

import com.xammer.cloud.domain.BillingHistorySnapshot;
import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.repository.BillingHistorySnapshotRepository;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketplaceBillingExtractionService {

    // Fixed date range: February 2025 – March 2026 (temp: within 14-month CE limit)
    // TODO: change EXTRACTION_START back to "2024-12-01" after enabling
    //       "Multi-year data" in Cost Explorer on the payer account.
    static final String EXTRACTION_START = "2025-02-01";
    static final String EXTRACTION_END   = "2026-04-01"; // exclusive — Cost Explorer end date

    /** Per-month S3 key: billing-archive/{accountId}/YYYY-MM/billing.csv */
    static String monthlyS3Key(String accountId, int year, int month) {
        return String.format("billing-archive/%s/%04d-%02d/billing.csv", accountId, year, month);
    }

    static String monthlyS3Key(String accountId, String yearMonth) {
        return String.format("billing-archive/%s/%s/billing.csv", accountId, yearMonth);
    }

    private final StsClient stsClient;
    private final MarketplacePayerAccountRepository payerAccountRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final BillingHistorySnapshotRepository snapshotRepository;
    private final FileStorageService fileStorageService;

    public MarketplaceBillingExtractionService(StsClient stsClient,
                                                MarketplacePayerAccountRepository payerAccountRepository,
                                                MarketplaceAccountRepository marketplaceAccountRepository,
                                                BillingHistorySnapshotRepository snapshotRepository,
                                                FileStorageService fileStorageService) {
        this.stsClient = stsClient;
        this.payerAccountRepository = payerAccountRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.snapshotRepository = snapshotRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Extract billing data for ALL marketplace accounts under the given payer.
     * Pulls USAGE_TYPE-level data from Cost Explorer, saves per-month CSVs to S3 and rows to DB.
     */
    public Map<String, String> extractAll(Long payerAccountId) {
        MarketplacePayerAccount payerAccount = payerAccountRepository.findById(payerAccountId)
                .orElseThrow(() -> new RuntimeException("Payer account not found: " + payerAccountId));

        List<MarketplaceAccount> allAccounts = marketplaceAccountRepository
                .findByPayerAccount(payerAccount);

        if (allAccounts.isEmpty()) {
            log.warn("No marketplace accounts found for payer {}. Run discovery first.",
                    payerAccount.getPayerAwsAccountId());
            return Collections.emptyMap();
        }

        log.info("Starting billing extraction for {} marketplace accounts ({} – {})",
                allAccounts.size(), EXTRACTION_START, EXTRACTION_END);

        // Assume the payer role once — it can read costs for all linked accounts
        StaticCredentialsProvider tempCreds = assumeRole(
                payerAccount.getRoleArn(), payerAccount.getExternalId());

        List<String> accountIds = allAccounts.stream()
                .map(MarketplaceAccount::getAwsAccountId)
                .collect(Collectors.toList());

        // Pull all data in one Cost Explorer call (GroupBy: LINKED_ACCOUNT + USAGE_TYPE)
        Map<String, List<CostRow>> dataByAccount = fetchFromCostExplorer(tempCreds, accountIds);

        // Process each account: save to DB + upload per-month CSVs to S3
        Map<String, String> results = new LinkedHashMap<>();
        for (MarketplaceAccount account : allAccounts) {
            String accountId = account.getAwsAccountId();
            List<CostRow> rows = dataByAccount.getOrDefault(accountId, Collections.emptyList());

            if (rows.isEmpty()) {
                log.warn("No cost data returned for account {} ({})", accountId, account.getAccountName());
                results.put(accountId, "NO_DATA");
                continue;
            }

            try {
                saveToDb(accountId, account.getAccountName(), rows);
                List<String> keys = uploadMonthlyCSVsToS3(accountId, account.getAccountName(), rows);

                account.setBillingExtracted(true);
                marketplaceAccountRepository.save(account);

                log.info("✅ Extracted {} rows ({} months) for account {}",
                        rows.size(), keys.size(), accountId);
                results.put(accountId, "SUCCESS:" + keys.size() + " months uploaded");

            } catch (Exception e) {
                log.error("❌ Failed to extract billing for account {}: {}", accountId, e.getMessage());
                results.put(accountId, "ERROR:" + e.getMessage());
            }
        }

        log.info("Extraction complete. Results: {}", results);
        return results;
    }

    /**
     * Extract billing for a single account (used by on-demand share flow).
     * Uploads one CSV per month: billing-archive/{accountId}/YYYY-MM/billing.csv
     * Returns list of S3 keys uploaded.
     */
    public List<String> extractForAccount(String awsAccountId, MarketplacePayerAccount payerAccount) {
        StaticCredentialsProvider tempCreds = assumeRole(
                payerAccount.getRoleArn(), payerAccount.getExternalId());

        Map<String, List<CostRow>> data = fetchFromCostExplorer(tempCreds, List.of(awsAccountId));
        List<CostRow> rows = data.getOrDefault(awsAccountId, Collections.emptyList());

        String accountName = marketplaceAccountRepository.findByAwsAccountId(awsAccountId)
                .map(MarketplaceAccount::getAccountName)
                .orElse(awsAccountId);

        if (rows.isEmpty()) {
            log.warn("No cost data for account {} in range {} to {}", awsAccountId,
                    EXTRACTION_START, EXTRACTION_END);
            return Collections.emptyList();
        }

        saveToDb(awsAccountId, accountName, rows);
        return uploadMonthlyCSVsToS3(awsAccountId, accountName, rows);
    }

    /**
     * Returns distinct year-month strings (e.g. ["2025-02", "2025-03"]) for an account.
     */
    public List<String> getAvailableMonths(String accountId) {
        return snapshotRepository.findByAwsAccountIdOrderByYearAscMonthAsc(accountId)
                .stream()
                .map(s -> String.format("%04d-%02d", s.getYear(), s.getMonth()))
                .distinct()
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private Map<String, List<CostRow>> fetchFromCostExplorer(StaticCredentialsProvider creds,
                                                              List<String> accountIds) {
        Map<String, List<CostRow>> result = new HashMap<>();

        try (CostExplorerClient ceClient = CostExplorerClient.builder()
                .region(Region.US_EAST_1) // Cost Explorer only in us-east-1
                .credentialsProvider(creds)
                .build()) {

            String nextPageToken = null;
            int pageCount = 0;

            do {
                GetCostAndUsageRequest.Builder reqBuilder = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(EXTRACTION_START)
                                .end(EXTRACTION_END)
                                .build())
                        .granularity(Granularity.MONTHLY)
                        // Fetch both cost and usage quantity for detailed billing data
                        .metrics("UnblendedCost", "UsageQuantity")
                        // GroupBy: LINKED_ACCOUNT + USAGE_TYPE (max 2 dimensions allowed)
                        .groupBy(
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("LINKED_ACCOUNT")
                                        .build(),
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("USAGE_TYPE")
                                        .build()
                        )
                        .filter(Expression.builder()
                                .dimensions(DimensionValues.builder()
                                        .key(Dimension.LINKED_ACCOUNT)
                                        .values(accountIds)
                                        .build())
                                .build());

                if (nextPageToken != null) {
                    reqBuilder.nextPageToken(nextPageToken);
                }

                GetCostAndUsageResponse response = ceClient.getCostAndUsage(reqBuilder.build());
                pageCount++;

                for (ResultByTime rbt : response.resultsByTime()) {
                    String monthStart = rbt.timePeriod().start(); // e.g. "2025-02-01"
                    int year  = Integer.parseInt(monthStart.substring(0, 4));
                    int month = Integer.parseInt(monthStart.substring(5, 7));

                    for (Group group : rbt.groups()) {
                        String accountId = group.keys().get(0);
                        String usageType = group.keys().get(1);

                        double cost = Double.parseDouble(
                                group.metrics().get("UnblendedCost").amount());
                        double quantity = Double.parseDouble(
                                group.metrics().get("UsageQuantity").amount());

                        if (cost < 0.0001) continue; // skip zero-cost rows

                        result.computeIfAbsent(accountId, k -> new ArrayList<>())
                              .add(new CostRow(year, month, usageType, quantity, cost));
                    }
                }

                nextPageToken = response.nextPageToken();

            } while (nextPageToken != null);

            log.info("Cost Explorer returned data across {} page(s) for {} accounts",
                    pageCount, accountIds.size());
        }

        return result;
    }

    /** Save rows to billing_history_snapshot table (delete existing first) */
    private void saveToDb(String accountId, String accountName, List<CostRow> rows) {
        snapshotRepository.deleteByAwsAccountId(accountId);

        List<BillingHistorySnapshot> snapshots = rows.stream()
                .map(row -> new BillingHistorySnapshot(
                        accountId, accountName,
                        row.year, row.month,
                        row.usageType,
                        BigDecimal.valueOf(row.costUsd),
                        row.usageQuantity))
                .collect(Collectors.toList());

        snapshotRepository.saveAll(snapshots);
        log.info("Saved {} snapshot rows to DB for account {}", snapshots.size(), accountId);
    }

    /**
     * Group rows by month, build one CSV per month, upload each to S3.
     * Returns list of uploaded S3 keys.
     */
    private List<String> uploadMonthlyCSVsToS3(String accountId, String accountName, List<CostRow> rows) {
        // Group by "YYYY-MM"
        Map<String, List<CostRow>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(r -> String.format("%04d-%02d", r.year, r.month)));

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<CostRow>> entry : byMonth.entrySet()) {
            String yearMonth = entry.getKey();
            String csvContent = buildCsv(accountId, accountName, entry.getValue());
            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            String s3Key = monthlyS3Key(accountId, yearMonth);
            fileStorageService.uploadFileBytes(s3Key, csvBytes, "text/csv");
            keys.add(s3Key);
            log.info("Uploaded {} rows for {}/{} → S3: {}",
                    entry.getValue().size(), accountId, yearMonth, s3Key);
        }
        return keys;
    }

    // ----------------------------------------------------------------
    // Region code → human-readable name (Cost Explorer prefix format)
    // ----------------------------------------------------------------
    private static final Map<String, String> REGION_PREFIX_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("USE1",   "US East (N. Virginia)");
        m.put("USE2",   "US East (Ohio)");
        m.put("USW1",   "US West (N. California)");
        m.put("USW2",   "US West (Oregon)");
        m.put("CAC1",   "Canada (Central)");
        m.put("CAN1",   "Canada (Calgary)");
        m.put("EUW1",   "Europe (Ireland)");
        m.put("EUW2",   "Europe (London)");
        m.put("EUW3",   "Europe (Paris)");
        m.put("EUC1",   "Europe (Frankfurt)");
        m.put("EUS1",   "Europe (Stockholm)");
        m.put("EUN1",   "Europe (Milan)");
        m.put("EUS2",   "Europe (Zurich)");
        m.put("EUS3",   "Europe (Spain)");
        m.put("APS1",   "Asia Pacific (Singapore)");
        m.put("APS2",   "Asia Pacific (Sydney)");
        m.put("APS3",   "Asia Pacific (Mumbai)");
        m.put("APS4",   "Asia Pacific (Seoul)");
        m.put("APS5",   "Asia Pacific (Hyderabad)");
        m.put("APE1",   "Asia Pacific (Tokyo)");
        m.put("APN1",   "Asia Pacific (Tokyo)");
        m.put("APN2",   "Asia Pacific (Seoul)");
        m.put("APN3",   "Asia Pacific (Osaka)");
        m.put("APE2",   "Asia Pacific (Osaka)");
        m.put("SAE1",   "South America (São Paulo)");
        m.put("MES1",   "Middle East (UAE)");
        m.put("MES2",   "Middle East (Bahrain)");
        m.put("AFR1",   "Africa (Cape Town)");
        m.put("ILL1",   "Israel (Tel Aviv)");
        m.put("MXC1",   "Mexico (Central)");
        REGION_PREFIX_MAP = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // UsageType prefix → AWS service name
    // ----------------------------------------------------------------
    private static final Map<String, String> SERVICE_PREFIX_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // EC2
        m.put("BoxUsage",            "Amazon Elastic Compute Cloud");
        m.put("SpotUsage",           "Amazon Elastic Compute Cloud");
        m.put("DedicatedUsage",      "Amazon Elastic Compute Cloud");
        m.put("HostBoxUsage",        "Amazon Elastic Compute Cloud");
        m.put("NatGateway",          "Amazon Elastic Compute Cloud");
        m.put("RegionalNatGateway",  "Amazon Elastic Compute Cloud");
        m.put("VpcEndpoint",         "Amazon Elastic Compute Cloud");
        m.put("PublicIPv4",          "Amazon Elastic Compute Cloud");
        m.put("EBSOptimized",        "Amazon Elastic Compute Cloud");
        m.put("CPUCredits",          "Amazon Elastic Compute Cloud");
        // EBS
        m.put("EBS:",                "Amazon Elastic Block Store");
        m.put("EBS-",                "Amazon Elastic Block Store");
        m.put("SnapshotUsage",       "Amazon Elastic Block Store");
        // ELB
        m.put("LoadBalancer",        "Elastic Load Balancing");
        m.put("LCU-",                "Elastic Load Balancing");
        m.put("LCUUsage",            "Elastic Load Balancing");
        m.put("DataProcessing",      "Elastic Load Balancing");
        // Data Transfer
        m.put("DataTransfer",        "AWS Data Transfer");
        m.put("Bandwidth",           "AWS Data Transfer");
        m.put("AWS-Out-Bytes",       "AWS Data Transfer");
        m.put("AWS-In-Bytes",        "AWS Data Transfer");
        m.put("CloudFront-",         "AWS Data Transfer");
        // S3
        m.put("Requests-",           "Amazon Simple Storage Service");
        m.put("TimedStorage-RabbitMQ", "Amazon MQ");
        m.put("TimedStorage",        "Amazon Simple Storage Service");
        m.put("IATimedStorage",      "Amazon Simple Storage Service");
        m.put("StorageLens",         "Amazon Simple Storage Service");
        m.put("Tables-",             "Amazon Simple Storage Service");
        m.put("S3-",                 "Amazon Simple Storage Service");
        m.put("Glacier-",            "Amazon S3 Glacier");
        m.put("AmazonGlacier",       "Amazon S3 Glacier");
        // RDS
        m.put("RDS:",                "Amazon Relational Database Service");
        m.put("RDS-",                "Amazon Relational Database Service");
        m.put("Aurora:",             "Amazon Relational Database Service");
        m.put("InstanceUsage",            "Amazon Relational Database Service");
        m.put("HeavyUsage",               "Amazon Relational Database Service");
        m.put("PaidRDSvCPUMonitored",     "Amazon Relational Database Service");
        m.put("StorageIOUsage",           "Amazon Relational Database Service");
        m.put("StorageUsage",             "Amazon Relational Database Service");
        m.put("Multi-AZUsage",            "Amazon Relational Database Service");
        // Other
        m.put("ElastiCache:",        "Amazon ElastiCache");
        m.put("NodeUsage:",          "Amazon ElastiCache");
        m.put("RabbitMQ-",           "Amazon MQ");
        m.put("AmazonMQ",            "Amazon MQ");
        m.put("AmazonEKS",           "Amazon Elastic Kubernetes Service");
        m.put("AmazonECS",           "Amazon Elastic Container Service");
        m.put("ECR-",                "Amazon Elastic Container Registry");
        m.put("Fargate-",            "AWS Fargate");
        m.put("AmazonEMR",           "Amazon EMR");
        m.put("EMR-",                "Amazon EMR");
        m.put("AWSBatch-",           "AWS Batch");
        m.put("Lambda-",             "AWS Lambda");
        m.put("Lambda:",             "AWS Lambda");
        m.put("Request-ARM",         "AWS Lambda");
        m.put("Request-",            "AWS Lambda");
        m.put("ApiGateway",          "Amazon API Gateway");
        m.put("APIRequest",          "AWS Cost Explorer");
        m.put("APS-",                "Amazon Managed Service for Prometheus");
        m.put("CloudWatch:",         "Amazon CloudWatch");
        m.put("CW:",                 "Amazon CloudWatch");
        m.put("DashboardsUsageHour", "Amazon CloudWatch");
        m.put("Logs-",               "Amazon CloudWatch Logs");
        m.put("VendedLog",           "Amazon CloudWatch Logs");
        m.put("SNS-",                "Amazon Simple Notification Service");
        m.put("DeliveryAttempts",    "Amazon Simple Notification Service");
        m.put("OutboundSMS",         "Amazon Pinpoint");
        m.put("SQS-",                "Amazon Simple Queue Service");
        m.put("SES-",                "Amazon Simple Email Service");
        m.put("Recipients",          "Amazon Simple Email Service");
        m.put("DynamoDB:",           "Amazon DynamoDB");
        m.put("DDB-",                "Amazon DynamoDB");
        m.put("ReadRequestUnits",    "Amazon DynamoDB");
        m.put("WriteRequestUnits",   "Amazon DynamoDB");
        m.put("AmazonRoute53",       "Amazon Route 53");
        m.put("DNS-",                "Amazon Route 53");
        m.put("HostedZone",          "Amazon Route 53");
        m.put("TransitGateway",      "AWS Transit Gateway");
        m.put("VPN-",                "AWS VPN");
        m.put("DirectConnect-",      "AWS Direct Connect");
        m.put("KMS-",                "AWS Key Management Service");
        m.put("AWSSecretsManager",   "AWS Secrets Manager");
        m.put("SecretsManager",      "AWS Secrets Manager");
        m.put("RequestV2",           "AWS WAF");
        m.put("RuleV2",              "AWS WAF");
        m.put("WebACLV2",            "AWS WAF");
        m.put("WAF-",                "AWS WAF");
        m.put("Request",             "Amazon API Gateway");
        m.put("Shield",              "AWS Shield");
        m.put("PaidComplianceCheck",        "AWS Security Hub");
        m.put("SecurityHubProduct",         "AWS Security Hub");
        m.put("OtherProduct",               "AWS Security Hub");
        m.put("GuardDuty-",                 "Amazon GuardDuty");
        m.put("Config-",                    "AWS Config");
        m.put("ConfigurationItemRecorded",  "AWS Config");
        m.put("ConformancePackEvaluations", "AWS Config");
        m.put("PaidDataInventoryEvaluation","Amazon Macie");
        m.put("CognitoUserPools",    "Amazon Cognito");
        m.put("CognitoIdentityPool", "Amazon Cognito");
        m.put("Inspector2-",         "Amazon Inspector");
        m.put("AmazonInspector",     "Amazon Inspector");
        m.put("ACM-",                "AWS Certificate Manager");
        m.put("CloudTrail-",              "AWS CloudTrail");
        m.put("DataEventsRecorded",       "AWS CloudTrail");
        m.put("InsightsEvents",           "AWS CloudTrail");
        m.put("PaidEventsAnalyzed",       "AWS CloudTrail");
        m.put("PaidS3DataEventsAnalyzed", "AWS CloudTrail");
        m.put("SSM-",                "AWS Systems Manager");
        m.put("JustInTimeAccess",    "AWS Systems Manager");
        m.put("Glue-",               "AWS Glue");
        m.put("Athena-",             "Amazon Athena");
        m.put("DataScannedInTB",     "Amazon Athena");
        m.put("SageMaker-",          "Amazon SageMaker");
        m.put("Comprehend-",         "Amazon Comprehend");
        m.put("Textract-",           "Amazon Textract");
        m.put("Rekognition",         "Amazon Rekognition");
        m.put("Polly-",              "Amazon Polly");
        m.put("Transcribe",          "Amazon Transcribe");
        m.put("Translate-",          "Amazon Translate");
        m.put("Lex-",                "Amazon Lex");
        m.put("Forecast-",           "Amazon Forecast");
        m.put("Personalize-",        "Amazon Personalize");
        m.put("Redshift:",           "Amazon Redshift");
        m.put("OpenSearch",          "Amazon OpenSearch Service");
        m.put("ES:",                 "Amazon OpenSearch Service");
        m.put("MSK:",                "Amazon Managed Streaming for Apache Kafka");
        m.put("Kinesis-",            "Amazon Kinesis");
        m.put("KDF-",                "Amazon Kinesis Data Firehose");
        m.put("CodeBuild-",          "AWS CodeBuild");
        m.put("BuildDuration",       "AWS CodeBuild");
        m.put("CP-",                 "AWS CodePipeline");
        m.put("AWSStepFunctions-",   "AWS Step Functions");
        m.put("AppSync-",            "AWS AppSync");
        m.put("AmazonVPC",           "Amazon VPC");
        m.put("ClientVPN",           "AWS Client VPN");
        m.put("BilledBytes",         "AWS PrivateLink");
        m.put("Std-MicrosoftAD",     "AWS Directory Service");
        m.put("Enterprise-MicrosoftAD", "AWS Directory Service");
        m.put("SmallBusiness-MicrosoftAD", "AWS Directory Service");
        m.put("Event-",              "Amazon EventBridge");
        m.put("ArchivedEvents",      "Amazon EventBridge");
        m.put("HostingComputeRequest", "AWS Amplify");
        m.put("DataStorage",         "AWS Amplify");
        m.put("AWSIoT-",             "AWS IoT Core");
        m.put("AmazonConnect-",      "Amazon Connect");
        m.put("WorkSpaces-",         "Amazon WorkSpaces");
        m.put("DataSync-",           "AWS DataSync");
        m.put("AWSTransfer-",        "AWS Transfer Family");
        m.put("AmazonLightsail",     "Amazon Lightsail");
        m.put("Bundle:",             "Amazon Lightsail");
        m.put("BundleUsage",         "Amazon Lightsail");
        m.put("AmazonEFS",           "Amazon Elastic File System");
        m.put("ProvisionedTP",       "Amazon Elastic File System");
        m.put("WarmStorage",         "Amazon Elastic File System");
        m.put("ETDataAccess",        "Amazon Timestream");
        m.put("AttachmentsSize",     "Amazon WorkMail");
        m.put("TagStorage",          "AWS Resource Groups Tagging API");
        m.put("Monitoring",          "Amazon DevOps Guru");
        m.put("Llama4",              "Amazon Bedrock");
        m.put("Claude",              "Amazon Bedrock");
        m.put("Titan",               "Amazon Bedrock");
        m.put("Mistral",             "Amazon Bedrock");
        m.put("Bedrock",             "Amazon Bedrock");
        m.put("MP:",                 "Amazon Bedrock");
        m.put("Nova",                "Amazon Bedrock");
        m.put("Cohere",              "Amazon Bedrock");
        m.put("BackupUsage",         "AWS Backup");
        m.put("ComputeSP",           "AWS Compute Savings Plans");
        SERVICE_PREFIX_MAP = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // KMS lowercase region format: ap-south-1-KMS-Requests
    // ----------------------------------------------------------------
    private static final Map<String, String> KMS_REGION_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ap-south-1",     "Asia Pacific (Mumbai)");
        m.put("ap-south-2",     "Asia Pacific (Hyderabad)");
        m.put("ap-southeast-1", "Asia Pacific (Singapore)");
        m.put("ap-southeast-2", "Asia Pacific (Sydney)");
        m.put("ap-southeast-3", "Asia Pacific (Jakarta)");
        m.put("ap-northeast-1", "Asia Pacific (Tokyo)");
        m.put("ap-northeast-2", "Asia Pacific (Seoul)");
        m.put("ap-northeast-3", "Asia Pacific (Osaka)");
        m.put("us-east-1",      "US East (N. Virginia)");
        m.put("us-east-2",      "US East (Ohio)");
        m.put("us-west-1",      "US West (N. California)");
        m.put("us-west-2",      "US West (Oregon)");
        m.put("eu-west-1",      "Europe (Ireland)");
        m.put("eu-west-2",      "Europe (London)");
        m.put("eu-west-3",      "Europe (Paris)");
        m.put("eu-central-1",   "Europe (Frankfurt)");
        m.put("eu-north-1",     "Europe (Stockholm)");
        m.put("eu-south-1",     "Europe (Milan)");
        m.put("eu-south-2",     "Europe (Spain)");
        m.put("eu-central-2",   "Europe (Zurich)");
        m.put("ca-central-1",   "Canada (Central)");
        m.put("ca-west-1",      "Canada (Calgary)");
        m.put("sa-east-1",      "South America (São Paulo)");
        m.put("me-south-1",     "Middle East (Bahrain)");
        m.put("me-central-1",   "Middle East (UAE)");
        m.put("af-south-1",     "Africa (Cape Town)");
        m.put("il-central-1",   "Israel (Tel Aviv)");
        KMS_REGION_MAP = Collections.unmodifiableMap(m);
    }

    // Matches CE uppercase region prefix: APS3, USE1, EUW2, etc.
    private static final Pattern REGION_STRIP_PATTERN =
            Pattern.compile("^([A-Z]{2,4}\\d)[-:](.*)$");

    // Matches global 2-letter/3-letter prefixes without a digit: IN-, EU-, US-, AP-, JP-, ME-, etc.
    private static final Pattern GLOBAL_PREFIX_PATTERN =
            Pattern.compile("^(IN|EU|US|AP|AU|CA|SA|JP|ME|ZA|BR|AR|GB)-.*");

    /** Strips the CE region prefix (e.g. "APS3") or "Global-" prefix, returns the suffix. */
    private static String stripRegionPrefix(String usageType) {
        // Handle Global-prefixed WAF usage types: Global-RuleV2, Global-WebACLV2
        if (usageType.startsWith("Global-")) {
            return usageType.substring(7);
        }
        Matcher m = REGION_STRIP_PATTERN.matcher(usageType);
        return m.matches() ? m.group(2) : usageType;
    }

    /** Returns the human-readable AWS region name from a usage type. */
    private static String extractRegion(String usageType) {
        // Global-prefixed WAF usage types
        if (usageType.startsWith("Global-")) {
            return "Global";
        }
        // KMS lowercase format: ap-south-1-KMS-Requests, us-east-1-KMS-Keys
        for (Map.Entry<String, String> entry : KMS_REGION_MAP.entrySet()) {
            if (usageType.startsWith(entry.getKey() + "-")) {
                return entry.getValue();
            }
        }
        // CE uppercase prefix: APS3-BoxUsage:t3.micro
        Matcher m = REGION_STRIP_PATTERN.matcher(usageType);
        if (m.matches()) {
            return REGION_PREFIX_MAP.getOrDefault(m.group(1), m.group(1));
        }
        // Global 2-letter/3-letter prefix (CloudFront/data transfer): IN-*, JP-*, ME-*, etc.
        if (GLOBAL_PREFIX_PATTERN.matcher(usageType).matches()) {
            return "Global";
        }
        return "Global";
    }

    /** Derives an AWS service name from a usage type string. */
    private static String extractService(String usageType) {
        // KMS lowercase format: ap-south-1-KMS-Requests, ap-south-2-KMS-Keys
        if (usageType.contains("-KMS-") || usageType.contains("-KMS$")) {
            return "AWS Key Management Service";
        }

        String suffix = stripRegionPrefix(usageType);

        // Cross-region data transfer: APS3-APS1-AWS-Out-Bytes
        // After stripping source region (APS3), suffix is APS1-AWS-Out-Bytes
        if (suffix.contains("-AWS-Out-Bytes") || suffix.contains("-AWS-In-Bytes")) {
            return "AWS Data Transfer";
        }

        // Global 2-letter/3-letter prefix (CloudFront/global data transfer): IN-*, JP-*, ME-*, etc.
        if (GLOBAL_PREFIX_PATTERN.matcher(usageType).matches()) {
            return "AWS Data Transfer";
        }

        for (Map.Entry<String, String> entry : SERVICE_PREFIX_MAP.entrySet()) {
            if (suffix.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        // Fallback: first token before ':' or '-'
        String[] parts = suffix.split("[-:]", 2);
        return parts[0].isEmpty() ? usageType : parts[0];
    }

    /**
     * Builds a hierarchical CSV matching the AWS Bill PDF layout:
     *
     *   Service,Region,Usage Type,Usage Quantity,Cost (USD)
     *   Amazon Elastic Compute Cloud,,,,796.60
     *   ,Asia Pacific (Hyderabad),,,394.54
     *   ,,APS5-NatGateway-Hours,1346.0000,75.38
     *   Grand Total,,,,1809.86
     */
    private String buildCsv(String accountId, String accountName, List<CostRow> rows) {
        StringBuilder sb = new StringBuilder();

        // Meta header
        sb.append("Account ID:,").append(escapecsv(accountId)).append('\n');
        sb.append("Account Name:,").append(escapecsv(accountName)).append('\n');
        sb.append('\n');

        // Column header
        sb.append("Service,Region,Usage Type,Usage Quantity,Cost (USD)\n");

        // Group: service → region → rows (sorted within each group by usage type)
        Map<String, Map<String, List<CostRow>>> byServiceRegion = new TreeMap<>();
        for (CostRow row : rows) {
            String service = extractService(row.usageType);
            String region  = extractRegion(row.usageType);
            byServiceRegion
                .computeIfAbsent(service, s -> new TreeMap<>())
                .computeIfAbsent(region,  r -> new ArrayList<>())
                .add(row);
        }

        double grandTotal = 0.0;

        for (Map.Entry<String, Map<String, List<CostRow>>> svcEntry : byServiceRegion.entrySet()) {
            String serviceName = svcEntry.getKey();
            double serviceTotal = svcEntry.getValue().values().stream()
                    .flatMap(Collection::stream)
                    .mapToDouble(r -> r.costUsd)
                    .sum();

            // Service row
            sb.append(escapecsv(serviceName)).append(",,,,")
              .append(String.format("%.2f", serviceTotal)).append('\n');

            for (Map.Entry<String, List<CostRow>> regEntry : svcEntry.getValue().entrySet()) {
                String regionName = regEntry.getKey();
                List<CostRow> regionRows = regEntry.getValue();
                double regionTotal = regionRows.stream().mapToDouble(r -> r.costUsd).sum();

                // Region row
                sb.append(',').append(escapecsv(regionName)).append(",,,")
                  .append(String.format("%.2f", regionTotal)).append('\n');

                // Detail rows — sort by usage type
                regionRows.stream()
                        .sorted(Comparator.comparing(r -> r.usageType))
                        .forEach(row -> {
                            sb.append(",,");
                            sb.append(escapecsv(row.usageType)).append(',');
                            sb.append(String.format("%.4f", row.usageQuantity)).append(',');
                            sb.append(String.format("%.6f", row.costUsd)).append('\n');
                        });
            }

            grandTotal += serviceTotal;
        }

        // Grand total row
        sb.append('\n');
        sb.append("Grand Total,,,,").append(String.format("%.2f", grandTotal)).append('\n');

        return sb.toString();
    }

    private String escapecsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        AssumeRoleResponse response = stsClient.assumeRole(r -> r
                .roleArn(roleArn)
                .roleSessionName("xamops-billing-extraction")
                .externalId(externalId)
                .durationSeconds(3600)
        );
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken())
        );
    }

    /** Simple value object for a single cost row */
    static class CostRow {
        final int year;
        final int month;
        final String usageType;
        final double usageQuantity;
        final double costUsd;

        CostRow(int year, int month, String usageType, double usageQuantity, double costUsd) {
            this.year = year;
            this.month = month;
            this.usageType = usageType != null ? usageType : "";
            this.usageQuantity = usageQuantity;
            this.costUsd = costUsd;
        }
    }
}
