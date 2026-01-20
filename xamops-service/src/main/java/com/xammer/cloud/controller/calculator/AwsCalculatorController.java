package com.xammer.cloud.controller.calculator;

import com.xammer.cloud.domain.calculator.ServiceConfig;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.calculator.aws.AwsServiceRegistry;
import com.xammer.cloud.service.calculator.aws.pricing.ComputePricingService;
import com.xammer.cloud.service.calculator.aws.pricing.DatabasePricingService;
import com.xammer.cloud.service.calculator.aws.pricing.NetworkingPricingService;
import com.xammer.cloud.service.calculator.aws.pricing.StoragePricingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/aws/calculator")
@CrossOrigin(origins = "*")
public class AwsCalculatorController {

    private final AwsServiceRegistry awsServiceRegistry;
    private final AwsPricingService awsPricingService;
    private final ComputePricingService computePricingService;
    private final StoragePricingService storagePricingService;
    private final NetworkingPricingService networkingPricingService;
    private final DatabasePricingService databasePricingService;

    public AwsCalculatorController(AwsServiceRegistry awsServiceRegistry,
                                   AwsPricingService awsPricingService,
                                   ComputePricingService computePricingService,
                                   StoragePricingService storagePricingService,
                                   NetworkingPricingService networkingPricingService,
                                   DatabasePricingService databasePricingService) {
        this.awsServiceRegistry = awsServiceRegistry;
        this.awsPricingService = awsPricingService;
        this.computePricingService = computePricingService;
        this.storagePricingService = storagePricingService;
        this.networkingPricingService = networkingPricingService;
        this.databasePricingService = databasePricingService;
    }

    @GetMapping("/regions")
    public ResponseEntity<Map<String, Object>> getAwsRegions(@RequestParam(defaultValue = "default") String accountId) {
        return ResponseEntity.ok(awsPricingService.getAwsRegions(accountId));
    }

    @GetMapping("/services")
    public ResponseEntity<Map<String, Object>> getAllServices() {
        Map<String, Object> officialData = awsPricingService.getAllAwsServices(awsServiceRegistry);
        List<Map<String, String>> officialServices = (List<Map<String, String>>) officialData.get("services");
        List<Map<String, String>> configuredServices = awsServiceRegistry.getServicesAsList();

        Map<String, Map<String, String>> mergedMap = new HashMap<>();

        if (officialServices != null) {
            for (Map<String, String> svc : officialServices) {
                mergedMap.put(svc.get("code"), svc);
            }
        }

        for (Map<String, String> svc : configuredServices) {
            mergedMap.put(svc.get("code"), svc);
        }

        List<Map<String, String>> finalServices = new ArrayList<>(mergedMap.values());
        finalServices.sort(Comparator.comparing(s -> s.get("name"), String.CASE_INSENSITIVE_ORDER));

        Map<String, Object> res = new HashMap<>();
        res.put("services", finalServices);
        res.put("count", finalServices.size());
        
        return ResponseEntity.ok(res);
    }

    @GetMapping(value = "/service-config/{serviceCode}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getServiceConfig(@PathVariable String serviceCode) {
        ServiceConfig config = awsServiceRegistry.getServiceConfig(serviceCode);
        if (config == null) return ResponseEntity.notFound().build();
        
        Map<String, Object> res = new HashMap<>();
        res.put("fields", config.getFields());
        res.put("pricingEndpoint", config.getPricingEndpoint());
        res.put("serviceName", config.getServiceName());
        return ResponseEntity.ok(res);
    }

    @GetMapping(value = "/s3/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getS3Estimate(
            @RequestParam String region,
            @RequestParam String storageClass,
            @RequestParam(defaultValue = "0") double storageGB,
            @RequestParam(defaultValue = "0") long putRequests,
            @RequestParam(defaultValue = "0") long getRequests,
            @RequestParam(defaultValue = "0") double dataTransferOutGB) {
        
        return ResponseEntity.ok(storagePricingService.getComprehensiveS3Estimate(
                "default", region, (int) storageGB, storageClass, (int) dataTransferOutGB, (int) putRequests, (int) getRequests));
    }

    @GetMapping("/ec2/instance-types")
    public ResponseEntity<Map<String, Object>> getEc2InstanceTypes(
            @RequestParam(defaultValue = "default") String accountId,
            @RequestParam String region, @RequestParam(defaultValue = "Linux") String os) {
        return ResponseEntity.ok(computePricingService.getEc2InstancePrices(accountId, region, os));
    }

    @GetMapping(value = "/ec2/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getComprehensiveEc2Estimate(
            @RequestParam(defaultValue = "default") String accountId,
            @RequestParam String region, @RequestParam String instanceType,
            @RequestParam(defaultValue = "Linux") String os,
            @RequestParam(defaultValue = "1") int instanceCount,
            @RequestParam(defaultValue = "730") int hoursPerMonth,
            @RequestParam(defaultValue = "30") int storageGB,
            @RequestParam(defaultValue = "gp3") String storageType,
            @RequestParam(defaultValue = "0") int dataTransferOutGB) {
        
        return ResponseEntity.ok(computePricingService.getComprehensiveEc2Estimate(
                accountId, region, instanceType, os, instanceCount, hoursPerMonth, storageGB, storageType, dataTransferOutGB));
    }

    @GetMapping(value = "/eks/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getComprehensiveEksEstimate(
            @RequestParam String region, @RequestParam(defaultValue = "1") int clusters,
            @RequestParam(defaultValue = "Standard Support") String supportTier,
            @RequestParam(defaultValue = "EC2 Instances") String computeType,
            @RequestParam(required = false) String instanceType,
            @RequestParam(defaultValue = "2") int nodeCount,
            @RequestParam(defaultValue = "10") int avgPodCount,
            @RequestParam(defaultValue = "0.25") double vcpuPerPod,
            @RequestParam(defaultValue = "0.5") double memoryPerPod,
            @RequestParam(defaultValue = "20") int storageGB) {

        double clusterRate = supportTier.equals("Extended Support") ? 0.60 : 0.10;
        double clusterMonthly = clusters * clusterRate * 730;
        double computeMonthly = computeType.equals("EC2 Instances") ? (nodeCount * 0.096 * 730) : 
                               (avgPodCount * (vcpuPerPod * 0.04048 + memoryPerPod * 0.004445) * 730);
        double storageMonthly = (computeType.equals("EC2 Instances") ? nodeCount : avgPodCount) * storageGB * 0.08;

        Map<String, Object> res = new HashMap<>();
        res.put("totalMonthly", clusterMonthly + computeMonthly + storageMonthly);
        
        Map<String, Object> comp = new HashMap<>();
        comp.put("details", computeType + " Nodes");
        comp.put("monthlyCost", computeMonthly + clusterMonthly);
        res.put("compute", comp);
        
        return ResponseEntity.ok(res);
    }

    @GetMapping(value = "/lambda/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getComprehensiveLambdaEstimate(
            @RequestParam String region, @RequestParam String architecture,
            @RequestParam int memoryMB, @RequestParam long requestsPerMonth,
            @RequestParam int avgDurationMs, @RequestParam(defaultValue = "512") int ephemeralStorageMB) {
        
        double rate = architecture.equals("arm64") ? 0.0000133334 : 0.0000166667;
        double computeCost = Math.max(0, (requestsPerMonth * (avgDurationMs/1000.0) * (memoryMB/1024.0)) - 400000) * rate;
        double requestCost = Math.max(0, requestsPerMonth - 1000000) * 0.0000002;

        Map<String, Object> res = new HashMap<>();
        res.put("totalMonthly", computeCost + requestCost);
        return ResponseEntity.ok(res);
    }

    @GetMapping(value = "/rds/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getRdsEstimate(
            @RequestParam String region,
            @RequestParam String engine,
            @RequestParam String instanceClass,
            @RequestParam int storageGB,
            @RequestParam String storageType,
            @RequestParam(defaultValue = "false") boolean multiAZ,
            @RequestParam(defaultValue = "0") int backupStorageGB) {
        
        return ResponseEntity.ok(databasePricingService.getComprehensiveRdsEstimate(
                "default", region, engine, instanceClass, storageGB, storageType, multiAZ, backupStorageGB));
    }

    @GetMapping(value = "/dynamodb/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getDynamoDbEstimate(
            @RequestParam String region,
            @RequestParam String capacityMode,
            @RequestParam int storageGB,
            @RequestParam(defaultValue = "0") int readRequests,
            @RequestParam(defaultValue = "0") int writeRequests,
            @RequestParam(defaultValue = "0") int provisionedReadCapacity,
            @RequestParam(defaultValue = "0") int provisionedWriteCapacity,
            @RequestParam(defaultValue = "false") boolean enableBackup) {

        return ResponseEntity.ok(databasePricingService.getComprehensiveDynamoDbEstimate(
                "default", region, capacityMode, storageGB, readRequests, writeRequests, 
                provisionedReadCapacity, provisionedWriteCapacity, enableBackup));
    }

    /**
     * Endpoint for Aurora Serverless to fix the 404 error
     */
    @GetMapping(value = "/aurora-serverless/comprehensive-estimate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAuroraServerlessEstimate(
            @RequestParam String engine,
            @RequestParam double acuHoursPerMonth,
            @RequestParam int storageGB,
            @RequestParam(defaultValue = "0") int backupStorageGB,
            @RequestParam(defaultValue = "0") long ioRequests) {

        // Basic calculation based on Aurora Serverless V2 pricing (~$0.12 per ACU-hour)
        double acuCost = acuHoursPerMonth * 0.12;
        double storageCost = storageGB * 0.10;
        double ioCost = (ioRequests / 1_000_000.0) * 0.20;
        double backupCost = Math.max(0, backupStorageGB - storageGB) * 0.095;

        Map<String, Object> res = new HashMap<>();
        res.put("totalMonthly", acuCost + storageCost + ioCost + backupCost);
        res.put("acuCost", acuCost);
        res.put("storageCost", storageCost);
        res.put("ioCost", ioCost);
        
        return ResponseEntity.ok(res);
    }

    @GetMapping("/rds/instance-types")
    public ResponseEntity<Map<String, Object>> getRdsInstanceTypes(
            @RequestParam String region, 
            @RequestParam String engine) {
        Map<String, Object> res = new HashMap<>();
        res.put("instances", Arrays.asList("db.t3.micro", "db.t3.small", "db.t3.medium", "db.m5.large", "db.r5.large"));
        return ResponseEntity.ok(res);
    }
}