package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PricingService {

    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    private final PricingClient pricingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AwsClientProvider awsClientProvider;
    private final CostMockService costMockService;

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @Value("${pricing.ebs.fallback-price:0.10}")
    private double ebsFallbackPrice;

    /** AWS bills on a 730-hour month (365 * 24 / 12), not 720. */
    private static final double HOURS_PER_MONTH = 730.0;

    /**
     * Last-resort monthly figure when the RDS catalog lookup fails entirely. Kept as a
     * named constant so it is greppable, and always accompanied by a warning log so a
     * systematic lookup failure is visible rather than silently uniform.
     */
    private static final double RDS_FALLBACK_MONTHLY_PRICE = 20.0;

    /**
     * Standard public IPv4 address rate, $0.005/hour. Since February 2024 AWS bills this for
     * every public IPv4 address, whether associated with a running resource or not.
     */
    private static final double DEFAULT_PUBLIC_IPV4_HOURLY = 0.005;

    /**
     * Sentinel returned when a price genuinely could not be determined.
     *
     * <p>It is {@code 0.0} for backward compatibility, but callers must not treat it as
     * "this resource is free" — use {@link #isPriceAvailable(double)} and exclude the
     * resource from savings totals instead, so a lookup failure cannot silently understate
     * a customer's waste.
     */
    public static final double PRICE_UNAVAILABLE = 0.0;

    /**
     * Returns true when a price returned by this service represents a real rate rather
     * than a failed lookup.
     */
    public static boolean isPriceAvailable(double price) {
        return price > PRICE_UNAVAILABLE;
    }

    public PricingService(PricingClient pricingClient, AwsClientProvider awsClientProvider,
            CostMockService costMockService) {
        this.pricingClient = pricingClient;
        this.awsClientProvider = awsClientProvider;
        this.costMockService = costMockService;
    }

    @Cacheable(value = "spotPrice", key = "#account.awsAccountId + '-' + #region + '-' + #instanceType")
    public double getSpotInstancePrice(String instanceType, String region, CloudAccount account) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getSpotInstancePrice");
            return costMockService.getMockSpotInstancePrice(instanceType, region);
        }

        logger.info("Fetching spot price for instance type: {} in region: {}", instanceType, region);
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);

            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.fromValue(instanceType))
                    .productDescriptions("Linux/UNIX")
                    .startTime(Instant.now().minusSeconds(3600))
                    .endTime(Instant.now())
                    .build();

            Optional<SpotPrice> spotPrice = ec2Client.describeSpotPriceHistory(request).spotPriceHistory().stream()
                    .max(Comparator.comparing(SpotPrice::timestamp));

            if (spotPrice.isPresent()) {
                return Double.parseDouble(spotPrice.get().spotPrice());
            }
        } catch (Exception e) {
            logger.error("Failed to fetch spot price for instance type {} in region {}", instanceType, region, e);
        }
        return 0.0;
    }

    @Cacheable(value = "ebsPrice", key = "#region + '-' + #volumeType")
    public double getEbsGbMonthPrice(String region, String volumeType) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getEbsGbMonthPrice");
            return costMockService.getMockEbsGbMonthPrice(region, volumeType);
        }

        logger.info("Fetching live EBS price for region: {}, type: {}", region, volumeType);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter volumeApiNameFilter = Filter.builder().field("volumeApiName").value(volumeType).type("TERM_MATCH")
                .build();
        // productFamily is essential: without it, volumeApiName=gp3 also matches the
        // provisioned-IOPS SKU ($/IOPS-mo) and the throughput SKU ($/MBps-mo), and
        // priceList ordering is not contractual — so we could return either as GB-month.
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("Storage").type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, volumeApiNameFilter, productFamilyFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for EBS volume type {} in region {}. Using fallback price.",
                        volumeType, region);
                return ebsFallbackPrice;
            }

            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode terms = priceList.path("terms").path("OnDemand");
            if (terms.isMissingNode() || terms.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode priceDimensions = terms.elements().next().path("priceDimensions");
            if (priceDimensions.isMissingNode() || priceDimensions.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            return pricePerUnit.asDouble(ebsFallbackPrice);

        } catch (IOException e) {
            logger.error("Failed to parse pricing JSON for EBS", e);
            return ebsFallbackPrice;
        } catch (Exception e) {
            logger.error("Failed to fetch price for EBS volume type {} in region {}", volumeType, region, e);
            return ebsFallbackPrice;
        }
    }

    @Cacheable(value = "ec2Price", key = "#region + '-' + #instanceType")
    public double getEc2InstanceMonthlyPrice(String instanceType, String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getEc2InstanceMonthlyPrice");
            return costMockService.getMockEc2InstanceMonthlyPrice(instanceType, region);
        }

        logger.info("Fetching live EC2 price for region: {}, type: {}", region, instanceType);
        // Use regionCode (the raw region id) instead of location (human-readable description)
        // so that all regions — including newer ones not in any hardcoded map — work correctly.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(instanceType).type("TERM_MATCH").build();
        Filter tenancyFilter = Filter.builder().field("tenancy").value("Shared").type("TERM_MATCH").build();
        Filter osFilter = Filter.builder().field("operatingSystem").value("Linux").type("TERM_MATCH").build();
        Filter capacityFilter = Filter.builder().field("capacitystatus").value("Used").type("TERM_MATCH").build();
        Filter preinstalledSwFilter = Filter.builder().field("preInstalledSw").value("NA").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, instanceTypeFilter, tenancyFilter, osFilter, capacityFilter, preinstalledSwFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            List<String> priceList = response.priceList();

            // Previously this retried with capacitystatus and preInstalledSw dropped, which
            // admits UnusedCapacityReservation SKUs and SQL-licensed SKUs — both priced far
            // above on-demand Linux. Those two filters are what make the result correct, so
            // they are never relaxed. Instead we scan all returned SKUs and pick the cheapest
            // valid on-demand rate, which is deterministic regardless of priceList ordering.
            if (priceList.isEmpty()) {
                logger.warn("No on-demand price found for EC2 instance type {} in region {} "
                                + "(tenancy=Shared, os=Linux, capacitystatus=Used, preInstalledSw=NA)",
                        instanceType, region);
                return PRICE_UNAVAILABLE;
            }

            double cheapest = Double.MAX_VALUE;
            for (String priceJson : priceList) {
                double hourly = extractOnDemandPrice(objectMapper.readTree(priceJson));
                if (hourly > 0 && hourly < cheapest) {
                    cheapest = hourly;
                }
            }
            if (cheapest < Double.MAX_VALUE) {
                return cheapest * HOURS_PER_MONTH;
            }
            logger.warn("EC2 price list for {} in {} contained no positive on-demand rate", instanceType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch or parse price for EC2 instance type {} in region {}", instanceType, region, e);
        }
        return PRICE_UNAVAILABLE;
    }

    @Cacheable(value = "elasticIpPrice", key = "#region")
    public double getElasticIpMonthlyPrice(String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getElasticIpMonthlyPrice");
            return costMockService.getMockElasticIpMonthlyPrice(region);
        }

        logger.info("Fetching live Elastic IP price for region: {}", region);

        // *** CHANGED: Using more specific filters for Elastic IPs ***
        // regionCode rather than the human-readable location: the 16-entry map this
        // previously used guaranteed a miss for any region outside it, which meant every
        // lookup in an unmapped region silently returned a hardcoded fallback.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH")
                .build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("IP Address").type("TERM_MATCH")
                .build();

        // The previous third filter was usagetype = "<region>-UnusedAddr", which can never
        // match: AWS usage types use short prefixes (USE1-, APS1-) and carry no prefix at
        // all in us-east-1. Every call therefore fell through to the hardcoded fallback.
        //
        // Since February 2024 AWS charges for ALL public IPv4 addresses, attached or not, so
        // the relevant meter is the general IPv4 address charge rather than an idle-only one.
        // Scanning the returned SKUs by usagetype substring is prefix-independent.
        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, productFamilyFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No IP Address price list found for region {}; using the standard IPv4 rate", region);
                return DEFAULT_PUBLIC_IPV4_HOURLY * HOURS_PER_MONTH;
            }

            // Prefer an explicit in-use IPv4 meter, then any address meter.
            Double idleRate = null;
            Double anyRate = null;
            for (String priceJson : response.priceList()) {
                JsonNode node = objectMapper.readTree(priceJson);
                String usageType = node.path("product").path("attributes").path("usagetype").asText("");
                double price = extractOnDemandPrice(node);
                if (price <= 0) {
                    continue;
                }
                if (usageType.contains("PublicIPv4:InUseAddress") || usageType.contains("InUseAddress")) {
                    idleRate = idleRate == null ? price : Math.min(idleRate, price);
                } else if (usageType.contains("Addr")) {
                    anyRate = anyRate == null ? price : Math.min(anyRate, price);
                }
            }

            Double resolved = idleRate != null ? idleRate : anyRate;
            if (resolved != null) {
                return resolved * HOURS_PER_MONTH;
            }
            logger.warn("No usable IPv4 address rate found for region {}; using the standard rate", region);
            return DEFAULT_PUBLIC_IPV4_HOURLY * HOURS_PER_MONTH;
        } catch (Exception e) {
            logger.error("Failed to fetch price for Elastic IP in region {}", region, e);
            return DEFAULT_PUBLIC_IPV4_HOURLY * HOURS_PER_MONTH;
        }
    }

    @Cacheable(value = "rdsPrice", key = "#region + '-' + #dbInstance.dbInstanceClass()")
    public double getRdsInstanceMonthlyPrice(DBInstance dbInstance, String region) {
        // Use mock data if enabled
        if (useMockData) {
            logger.info("[MOCK] Using mock data for getRdsInstanceMonthlyPrice");
            return costMockService.getMockRdsInstanceMonthlyPrice(dbInstance, region);
        }

        logger.info("Fetching live RDS price for region: {}, type: {}", region, dbInstance.dbInstanceClass());
        // regionCode works for every region, including ones absent from the location map.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter instanceClassFilter = Filter.builder().field("instanceType").value(dbInstance.dbInstanceClass())
                .type("TERM_MATCH").build();
        // The RDS API reports engines as "postgres"/"mysql"; the Pricing API expects the
        // display form "PostgreSQL"/"MySQL". Without this mapping no SKU ever matched and
        // every instance fell through to the flat fallback below.
        Filter engineFilter = Filter.builder().field("databaseEngine")
                .value(toPricingDatabaseEngine(dbInstance.engine())).type("TERM_MATCH").build();
        // Single-AZ vs Multi-AZ is roughly a 2x difference, so it must be pinned.
        Filter deploymentFilter = Filter.builder().field("deploymentOption")
                .value(Boolean.TRUE.equals(dbInstance.multiAZ()) ? "Multi-AZ" : "Single-AZ")
                .type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonRDS")
                .filters(regionFilter, instanceClassFilter, engineFilter, deploymentFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            List<String> priceList = response.priceList();

            // Retry without deploymentOption: some engines (e.g. Aurora Serverless) do not
            // publish that dimension.
            if (priceList.isEmpty()) {
                priceList = pricingClient.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRDS")
                        .filters(regionFilter, instanceClassFilter, engineFilter)
                        .build()).priceList();
            }

            if (priceList.isEmpty()) {
                logger.warn("No price list found for RDS instance type {} engine {} in region {}. "
                                + "Falling back to a flat estimate; this figure is not authoritative.",
                        dbInstance.dbInstanceClass(), dbInstance.engine(), region);
                return RDS_FALLBACK_MONTHLY_PRICE;
            }
            JsonNode priceListNode = objectMapper.readTree(priceList.get(0));
            double hourlyPrice = extractOnDemandPrice(priceListNode);
            if (hourlyPrice <= 0) {
                logger.warn("RDS price lookup returned a non-positive rate for {} in {}; using fallback",
                        dbInstance.dbInstanceClass(), region);
                return RDS_FALLBACK_MONTHLY_PRICE;
            }
            return hourlyPrice * HOURS_PER_MONTH;
        } catch (Exception e) {
            logger.error("Failed to fetch price for RDS instance {} in region {}", dbInstance.dbInstanceIdentifier(),
                    region, e);
            return RDS_FALLBACK_MONTHLY_PRICE;
        }
    }

    /**
     * Translates an RDS API engine identifier into the {@code databaseEngine} value used
     * by the AWS Pricing API.
     *
     * <p>The two vocabularies differ: {@code DBInstance.engine()} returns values like
     * {@code postgres} and {@code aurora-postgresql}, while the price catalog uses
     * {@code PostgreSQL} and {@code Aurora PostgreSQL}. An unmapped engine is returned
     * unchanged so a newly launched engine degrades to the fallback rather than throwing.
     */
    private String toPricingDatabaseEngine(String rdsEngine) {
        if (rdsEngine == null || rdsEngine.isBlank()) {
            return "MySQL";
        }
        switch (rdsEngine.toLowerCase()) {
            case "postgres":
            case "postgresql":
                return "PostgreSQL";
            case "mysql":
                return "MySQL";
            case "mariadb":
                return "MariaDB";
            case "aurora":
            case "aurora-mysql":
                return "Aurora MySQL";
            case "aurora-postgresql":
                return "Aurora PostgreSQL";
            case "oracle-se2":
            case "oracle-se2-cdb":
            case "oracle-se":
            case "oracle-se1":
            case "oracle-ee":
            case "oracle-ee-cdb":
                return "Oracle";
            case "sqlserver-ex":
            case "sqlserver-web":
            case "sqlserver-se":
            case "sqlserver-ee":
                return "SQL Server";
            case "db2-se":
            case "db2-ae":
                return "Db2";
            default:
                logger.warn("Unmapped RDS engine '{}' for pricing lookup; passing through unchanged", rdsEngine);
                return rdsEngine;
        }
    }

    @Cacheable(value = "natGatewayPrice", key = "#region")
    public double getNatGatewayHourlyPrice(String region) {
        double fallback = 0.045;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live NAT Gateway price for region: {}", region);
        // regionCode rather than the human-readable location: the 16-entry map this
        // previously used guaranteed a miss for any region outside it, which meant every
        // lookup in an unmapped region silently returned a hardcoded fallback.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH")
                .build();
        Filter groupFilter = Filter.builder().field("group").value("NAT Gateway").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonVPC")
                .filters(regionFilter, groupFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            for (String priceJson : response.priceList()) {
                JsonNode priceListNode = objectMapper.readTree(priceJson);
                String usageType = priceListNode.path("product").path("attributes").path("usagetype").asText("");
                if (usageType.contains("NatGateway-Hours")) {
                    double price = extractOnDemandPrice(priceListNode);
                    if (price > 0) {
                        return price;
                    }
                }
            }
            logger.warn("No NAT Gateway hourly price found for region {}, using fallback", region);
        } catch (Exception e) {
            logger.error("Failed to fetch NAT Gateway price for region {}", region, e);
        }
        return fallback;
    }

    @Cacheable(value = "elbPrice", key = "#region + '-' + #lbType")
    public double getElbHourlyPrice(String region, String lbType) {
        double fallback = getElbHourlyFallback(lbType);
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live ELB price for region: {}, type: {}", region, lbType);
        // regionCode rather than the human-readable location: the 16-entry map this
        // previously used guaranteed a miss for any region outside it, which meant every
        // lookup in an unmapped region silently returned a hardcoded fallback.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH")
                .build();
        Filter usageTypeFilter = Filter.builder().field("usagetype").value("LoadBalancerUsage").type("TERM_MATCH")
                .build();
        Filter operationFilter = Filter.builder().field("operation").value(mapElbOperation(lbType)).type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AWSELB")
                .filters(regionFilter, usageTypeFilter, operationFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            for (String priceJson : response.priceList()) {
                double price = extractOnDemandPrice(objectMapper.readTree(priceJson));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No ELB price found for type {} in region {}, using fallback", lbType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch ELB price for type {} in region {}", lbType, region, e);
        }
        return fallback;
    }

    private String mapElbOperation(String lbType) {
        if (lbType == null) {
            return "LoadBalancing:Application";
        }
        switch (lbType.toLowerCase()) {
            case "network":
                return "LoadBalancing:Network";
            case "gateway":
                return "LoadBalancing:Gateway";
            case "classic":
                return "LoadBalancing";
            default:
                return "LoadBalancing:Application";
        }
    }

    private double getElbHourlyFallback(String lbType) {
        if (lbType == null) {
            return 0.0225;
        }
        switch (lbType.toLowerCase()) {
            case "gateway":
                return 0.0125;
            case "classic":
                return 0.025;
            default:
                return 0.0225;
        }
    }

    @Cacheable(value = "ebsSnapshotPrice", key = "#region")
    public double getEbsSnapshotGbMonthPrice(String region) {
        double fallback = 0.05;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live EBS snapshot price for region: {}", region);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("Storage Snapshot")
                .type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, productFamilyFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No EBS snapshot price found for region {}, using fallback", region);
        } catch (Exception e) {
            logger.error("Failed to fetch EBS snapshot price for region {}", region, e);
        }
        return fallback;
    }

    @Cacheable(value = "rdsSnapshotPrice", key = "#region + '-' + #engine")
    public double getRdsSnapshotGbMonthPrice(String region, String engine) {
        double fallback = 0.095;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live RDS snapshot price for region: {}, engine: {}", region, engine);
        // regionCode rather than the human-readable location: the 16-entry map this
        // previously used guaranteed a miss for any region outside it, which meant every
        // lookup in an unmapped region silently returned a hardcoded fallback.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH")
                .build();
        Filter productFamilyFilter = Filter.builder().field("productFamily").value("Storage Snapshot")
                .type("TERM_MATCH").build();
        Filter engineFilter = Filter.builder().field("databaseEngine").value(engine).type("TERM_MATCH").build();

        try {
            GetProductsResponse response = pricingClient.getProducts(GetProductsRequest.builder()
                    .serviceCode("AmazonRDS")
                    .filters(regionFilter, productFamilyFilter, engineFilter)
                    .build());
            List<String> priceList = response.priceList();
            if (priceList.isEmpty()) {
                // Snapshot storage pricing is often engine-agnostic; retry without the engine filter.
                priceList = pricingClient.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRDS")
                        .filters(regionFilter, productFamilyFilter)
                        .build()).priceList();
            }
            if (!priceList.isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(priceList.get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No RDS snapshot price found for region {}, engine {}, using fallback", region, engine);
        } catch (Exception e) {
            logger.error("Failed to fetch RDS snapshot price for region {}, engine {}", region, engine, e);
        }
        return fallback;
    }

    @Cacheable(value = "s3StoragePrice", key = "#region + '-' + #storageClass")
    public double getS3StorageClassGbMonthPrice(String region, String storageClass) {
        double fallback = "Standard-IA".equals(storageClass) ? 0.0125 : 0.023;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live S3 {} price for region: {}", storageClass, region);
        String volumeType = "Standard-IA".equals(storageClass) ? "Standard - Infrequent Access" : "Standard";
        // regionCode rather than the human-readable location: the 16-entry map this
        // previously used guaranteed a miss for any region outside it, which meant every
        // lookup in an unmapped region silently returned a hardcoded fallback.
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH")
                .build();
        Filter volumeTypeFilter = Filter.builder().field("volumeType").value(volumeType).type("TERM_MATCH").build();
        Filter usageTypeFilter = Filter.builder().field("usagetype").value("TimedStorage-ByteHrs").type("TERM_MATCH")
                .build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonS3")
                .filters(regionFilter, volumeTypeFilter, usageTypeFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No S3 {} price found for region {}, using fallback", storageClass, region);
        } catch (Exception e) {
            logger.error("Failed to fetch S3 {} price for region {}", storageClass, region, e);
        }
        return fallback;
    }

    @Cacheable(value = "elastiCachePrice", key = "#region + '-' + #nodeType")
    public double getElastiCacheNodeHourlyPrice(String region, String nodeType) {
        double fallback = 0.034;
        if (useMockData) {
            return fallback;
        }
        logger.info("Fetching live ElastiCache price for region: {}, node type: {}", region, nodeType);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(nodeType).type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonElastiCache")
                .filters(regionFilter, instanceTypeFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (!response.priceList().isEmpty()) {
                double price = extractOnDemandPrice(objectMapper.readTree(response.priceList().get(0)));
                if (price > 0) {
                    return price;
                }
            }
            logger.warn("No ElastiCache price found for node type {} in region {}, using fallback", nodeType, region);
        } catch (Exception e) {
            logger.error("Failed to fetch ElastiCache price for node type {} in region {}", nodeType, region, e);
        }
        return fallback;
    }

    /**
     * Extracts the first OnDemand price-per-unit (USD) from a Pricing API price-list entry.
     */
    private double extractOnDemandPrice(JsonNode priceListNode) {
        JsonNode onDemandTerms = priceListNode.path("terms").path("OnDemand");
        Iterator<JsonNode> termElements = onDemandTerms.elements();
        if (termElements.hasNext()) {
            JsonNode priceDimensions = termElements.next().path("priceDimensions");
            Iterator<JsonNode> dimElements = priceDimensions.elements();
            if (dimElements.hasNext()) {
                return dimElements.next().path("pricePerUnit").path("USD").asDouble(0.0);
            }
        }
        return 0.0;
    }

    private String getRegionDescription(String regionCode) {
        Map<String, String> regionMap = Map.ofEntries(
                Map.entry("us-east-1", "US East (N. Virginia)"),
                Map.entry("us-east-2", "US East (Ohio)"),
                Map.entry("us-west-1", "US West (N. California)"),
                Map.entry("us-west-2", "US West (Oregon)"),
                Map.entry("ap-south-1", "Asia Pacific (Mumbai)"),
                Map.entry("ap-northeast-2", "Asia Pacific (Seoul)"),
                Map.entry("ap-southeast-1", "Asia Pacific (Singapore)"),
                Map.entry("ap-southeast-2", "Asia Pacific (Sydney)"),
                Map.entry("ap-northeast-1", "Asia Pacific (Tokyo)"),
                Map.entry("ca-central-1", "Canada (Central)"),
                Map.entry("eu-central-1", "EU (Frankfurt)"),
                Map.entry("eu-west-1", "EU (Ireland)"),
                Map.entry("eu-west-2", "EU (London)"),
                Map.entry("eu-west-3", "EU (Paris)"),
                Map.entry("eu-north-1", "EU (Stockholm)"),
                Map.entry("sa-east-1", "South America (Sao Paulo)"));
        return regionMap.getOrDefault(regionCode, regionCode);
    }
}