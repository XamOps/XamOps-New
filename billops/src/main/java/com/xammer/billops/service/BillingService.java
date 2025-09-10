package com.xammer.billops.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class BillingService {

    // Mock data for "Top 5 Services by Cost" pie chart
    public Map<String, Double> getTopServicesData() {
        Map<String, Double> data = new HashMap<>();
        data.put("Amazon EC2", 1200.50);
        data.put("Amazon RDS", 850.75);
        data.put("Amazon S3", 450.25);
        data.put("AWS Lambda", 300.00);
        data.put("Other", 200.00);
        return data;
    }

    // Mock data for "Top 5 Regions by Cost" pie chart
    public Map<String, Double> getTopRegionsData() {
        Map<String, Double> data = new HashMap<>();
        data.put("us-east-1", 1500.50);
        data.put("us-west-2", 900.75);
        data.put("eu-west-1", 600.25);
        data.put("ap-southeast-1", 400.00);
        data.put("Other", 100.00);
        return data;
    }
    
    // Mock data for "Last 6 Months Consumption" bar graph
    public Map<String, Double> getMonthlyConsumptionData() {
        Map<String, Double> data = new HashMap<>();
        data.put("March", 2500.00);
        data.put("April", 2800.50);
        data.put("May", 2600.75);
        data.put("June", 3100.25);
        data.put("July", 3000.00);
        data.put("August", 3500.50);
        return data;
    }

    // Mock data for the detailed monthly breakdown table
    public List<Map<String, Object>> getDetailedMonthlyBilling(String month) {
        return Arrays.asList(
            createServiceDetail("Amazon EC2", 1200.50, 34.3),
            createServiceDetail("Amazon RDS", 850.75, 24.3),
            createServiceDetail("Amazon S3", 450.25, 12.8),
            createServiceDetail("AWS Lambda", 300.00, 8.6),
            createServiceDetail("Amazon VPC", 150.00, 4.3),
            createServiceDetail("AWS Key Management Service", 50.00, 1.4),
            createServiceDetail("Other", 504.00, 14.3)
        );
    }
    
    private Map<String, Object> createServiceDetail(String service, double cost, double percentage) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("service", service);
        detail.put("cost", cost);
        detail.put("percentage", percentage);
        return detail;
    }
    public Map<String, String> getBillingSummary() {
    Map<String, String> summary = new HashMap<>();
    summary.put("currentMonthSpend", "$3,501.50");
    summary.put("lastMonthSpend", "$3,000.00");
    summary.put("monthOverMonthChange", "+16.7%");
    summary.put("forecastedSpend", "$3,750.00");
    return summary;
}
}