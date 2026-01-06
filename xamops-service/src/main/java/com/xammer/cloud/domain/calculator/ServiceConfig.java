package com.xammer.cloud.domain.calculator;

import java.util.List;

/**
 * Domain model representing configuration for a cloud service in the calculator.
 * This is a common model used across AWS, GCP, and Azure calculators.
 */
public class ServiceConfig {
    private String serviceCode;
    private String serviceName;
    private String category;
    private List<FormField> fields;
    private String pricingEndpoint;

    public static ServiceConfigBuilder builder() {
        return new ServiceConfigBuilder();
    }

    // Getters
    public String getServiceCode() { return serviceCode; }
    public String getServiceName() { return serviceName; }
    public String getCategory() { return category; }
    public List<FormField> getFields() { return fields; }
    public String getPricingEndpoint() { return pricingEndpoint; }

    // Builder Pattern
    public static class ServiceConfigBuilder {
        private String serviceCode;
        private String serviceName;
        private String category;
        private List<FormField> fields;
        private String pricingEndpoint;

        public ServiceConfigBuilder serviceCode(String serviceCode) {
            this.serviceCode = serviceCode;
            return this;
        }

        public ServiceConfigBuilder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public ServiceConfigBuilder category(String category) {
            this.category = category;
            return this;
        }

        public ServiceConfigBuilder fields(List<FormField> fields) {
            this.fields = fields;
            return this;
        }

        public ServiceConfigBuilder pricingEndpoint(String pricingEndpoint) {
            this.pricingEndpoint = pricingEndpoint;
            return this;
        }

        public ServiceConfig build() {
            ServiceConfig config = new ServiceConfig();
            config.serviceCode = this.serviceCode;
            config.serviceName = this.serviceName;
            config.category = this.category;
            config.fields = this.fields;
            config.pricingEndpoint = this.pricingEndpoint;
            return config;
        }
    }
}
