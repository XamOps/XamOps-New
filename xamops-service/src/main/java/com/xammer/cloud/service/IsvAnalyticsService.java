package com.xammer.cloud.service;

import com.xammer.cloud.repository.IsvMarketplaceCustomerRepository;
import com.xammer.cloud.repository.MeteringRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides aggregated analytics for an ISV product:
 *
 *   customerGrowth  — new customer registrations per month
 *   usageByDimension — total quantity reported to AWS per dimension per month
 *   submissionHealth — SUCCESS vs FAILED metering records per month
 *
 * Data is derived entirely from the local database — no AWS API calls.
 */
@Service
public class IsvAnalyticsService {

    private final MeteringRecordRepository meteringRecordRepository;
    private final IsvMarketplaceCustomerRepository customerRepository;

    public IsvAnalyticsService(MeteringRecordRepository meteringRecordRepository,
                                IsvMarketplaceCustomerRepository customerRepository) {
        this.meteringRecordRepository = meteringRecordRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Returns analytics for the given product:
     * {
     *   "customerGrowth": [
     *     { "yearMonth": "2026-01", "newCustomers": 3 },
     *     ...
     *   ],
     *   "usageByDimension": [
     *     { "yearMonth": "2026-01", "dimension": "api_calls",    "totalQuantity": 45000 },
     *     { "yearMonth": "2026-01", "dimension": "active_users", "totalQuantity": 7 },
     *     ...
     *   ],
     *   "submissionHealth": [
     *     { "yearMonth": "2026-01", "success": 48, "failed": 2 },
     *     ...
     *   ]
     * }
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAnalytics(String productCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productCode", productCode);
        result.put("customerGrowth",    buildCustomerGrowth(productCode));
        result.put("usageByDimension",  buildUsageByDimension(productCode));
        result.put("submissionHealth",  buildSubmissionHealth(productCode));
        return result;
    }

    // ─── Builders ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildCustomerGrowth(String productCode) {
        List<Object[]> rows = customerRepository.countRegistrationsByMonth(productCode);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("yearMonth", row[0]);
            entry.put("newCustomers", row[1] != null ? ((Number) row[1]).longValue() : 0L);
            list.add(entry);
        }
        return list;
    }

    private List<Map<String, Object>> buildUsageByDimension(String productCode) {
        List<Object[]> rows = meteringRecordRepository.sumByDimensionByMonth(productCode);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("yearMonth",      row[0]);
            entry.put("dimension",      row[1]);
            entry.put("totalQuantity",  row[2] != null ? ((Number) row[2]).longValue() : 0L);
            list.add(entry);
        }
        return list;
    }

    private List<Map<String, Object>> buildSubmissionHealth(String productCode) {
        List<Object[]> rows = meteringRecordRepository.countByStatusByMonth(productCode);

        // Merge SUCCESS and FAILED counts into one row per month
        TreeMap<String, long[]> byMonth = new TreeMap<>(); // [success, failed]
        for (Object[] row : rows) {
            String yearMonth = (String) row[0];
            String status    = (String) row[1];
            long   count     = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            byMonth.computeIfAbsent(yearMonth, k -> new long[]{0L, 0L});
            if ("SUCCESS".equals(status))      byMonth.get(yearMonth)[0] += count;
            else if ("FAILED".equals(status))  byMonth.get(yearMonth)[1] += count;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byMonth.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("yearMonth", e.getKey());
            entry.put("success",   e.getValue()[0]);
            entry.put("failed",    e.getValue()[1]);
            list.add(entry);
        }
        return list;
    }
}
