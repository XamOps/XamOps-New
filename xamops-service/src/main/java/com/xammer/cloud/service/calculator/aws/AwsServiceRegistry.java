package com.xammer.cloud.service.calculator.aws;

import com.xammer.cloud.domain.calculator.ServiceConfig;
import com.xammer.cloud.service.calculator.aws.config.ComputeConfigService;
import com.xammer.cloud.service.calculator.aws.config.DatabaseConfigService;
import com.xammer.cloud.service.calculator.aws.config.NetworkingConfigService;
import com.xammer.cloud.service.calculator.aws.config.StorageConfigService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AWS Service Registry - Central registry for all AWS calculator services.
 * Aggregates configurations from all category-specific config services.
 */
@Service
public class AwsServiceRegistry {

    private final ComputeConfigService computeConfigService;
    private final StorageConfigService storageConfigService;
    private final NetworkingConfigService networkingConfigService;
    private final DatabaseConfigService databaseConfigService;

    private final Map<String, ServiceConfig> allAwsServices = new HashMap<>();

    public AwsServiceRegistry(ComputeConfigService computeConfigService,
                              StorageConfigService storageConfigService,
                              NetworkingConfigService networkingConfigService,
                              DatabaseConfigService databaseConfigService) {
        this.computeConfigService = computeConfigService;
        this.storageConfigService = storageConfigService;
        this.networkingConfigService = networkingConfigService;
        this.databaseConfigService = databaseConfigService;
        
        initializeRegistry();
    }

    private void initializeRegistry() {
        // Aggregate all AWS service configurations
        allAwsServices.putAll(computeConfigService.getAllComputeConfigs());
        allAwsServices.putAll(storageConfigService.getAllStorageConfigs());
        allAwsServices.putAll(networkingConfigService.getAllNetworkingConfigs());
        allAwsServices.putAll(databaseConfigService.getAllDatabaseConfigs());
    }

    /**
     * Get service configuration by service code
     */
    public ServiceConfig getServiceConfig(String serviceCode) {
        return allAwsServices.get(serviceCode);
    }

    /**
     * Get all AWS services
     */
    public Map<String, ServiceConfig> getAllServices() {
        return new HashMap<>(allAwsServices);
    }

    /**
     * Get services by category
     */
    public Map<String, ServiceConfig> getServicesByCategory(String category) {
        return allAwsServices.entrySet().stream()
                .filter(entry -> category.equalsIgnoreCase(entry.getValue().getCategory()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get all service codes grouped by category
     */
    public Map<String, List<Map<String, String>>> getServicesGroupedByCategory() {
        Map<String, List<Map<String, String>>> grouped = new HashMap<>();
        
        allAwsServices.forEach((code, config) -> {
            String category = config.getCategory();
            grouped.putIfAbsent(category, new ArrayList<>());
            
            Map<String, String> serviceInfo = new HashMap<>();
            serviceInfo.put("code", code);
            serviceInfo.put("name", config.getServiceName());
            serviceInfo.put("category", category);
            
            grouped.get(category).add(serviceInfo);
        });
        
        // Sort services within each category by name
        grouped.forEach((category, services) -> 
            services.sort(Comparator.comparing(s -> s.get("name")))
        );
        
        return grouped;
    }

    /**
     * Get list of all categories
     */
    public List<String> getAllCategories() {
        return allAwsServices.values().stream()
                .map(ServiceConfig::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Get service count by category
     */
    public Map<String, Integer> getServiceCountByCategory() {
        Map<String, Integer> counts = new HashMap<>();
        
        allAwsServices.forEach((code, config) -> {
            String category = config.getCategory();
            counts.put(category, counts.getOrDefault(category, 0) + 1);
        });
        
        return counts;
    }

    /**
     * Check if service exists
     */
    public boolean hasService(String serviceCode) {
        return allAwsServices.containsKey(serviceCode);
    }

    /**
     * Get total service count
     */
    public int getTotalServiceCount() {
        return allAwsServices.size();
    }

    /**
     * Get service metadata (for API responses)
     */
    public Map<String, Object> getServiceMetadata(String serviceCode) {
        ServiceConfig config = allAwsServices.get(serviceCode);
        if (config == null) {
            return null;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("serviceCode", config.getServiceCode());
        metadata.put("serviceName", config.getServiceName());
        metadata.put("category", config.getCategory());
        metadata.put("fieldCount", config.getFields() != null ? config.getFields().size() : 0);
        metadata.put("pricingEndpoint", config.getPricingEndpoint());
        
        return metadata;
    }

    /**
     * Get all services as a simple list (for dropdown/selection UI)
     */
    public List<Map<String, String>> getServicesAsList() {
        return allAwsServices.entrySet().stream()
                .map(entry -> {
                    Map<String, String> service = new HashMap<>();
                    service.put("code", entry.getKey());
                    service.put("name", entry.getValue().getServiceName());
                    service.put("category", entry.getValue().getCategory());
                    return service;
                })
                .sorted(Comparator.comparing(s -> s.get("name")))
                .collect(Collectors.toList());
    }
}
