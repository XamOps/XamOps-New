package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MetricPoller {

    private static final Logger log = LoggerFactory.getLogger(MetricPoller.class);

    private final OpenObserveService openObserveService;
    private final ObservabilityConfigRepository obsConfigRepo;

    public MetricPoller(OpenObserveService openObserveService,
                        ObservabilityConfigRepository obsConfigRepo) {
        this.openObserveService = openObserveService;
        this.obsConfigRepo = obsConfigRepo;
    }

    public Optional<Double> poll(SloDefinition slo) {
        var obsConfig = obsConfigRepo.findByClientId(slo.getClientId()).orElse(null);
        if (obsConfig == null) return Optional.empty();

        String sql = slo.getQueryTemplate()
            .replace("{stream}", slo.getStreamName())
            .replace("{entity}", slo.getEntity());

        long nowSec = System.currentTimeMillis() / 1000;
        Map<String, Object> params = new HashMap<>();
        params.put("providerAccountId", obsConfig.getO2OrgId());
        params.put("stream", slo.getStreamName());
        params.put("rawSql", sql);
        params.put("startTime", nowSec - slo.getWindowSeconds());
        params.put("endTime", nowSec);
        params.put("size", 1);

        try {
            Map<String, Object> result = openObserveService.searchLogs(slo.getClientId(), params);
            return extractScalar(result);
        } catch (Exception e) {
            log.warn("MetricPoller failed for slo={} entity={}: {}", slo.getId(), slo.getEntity(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> extractScalar(Map<String, Object> result) {
        Object hits = result.get("hits");
        if (!(hits instanceof List<?> list) || list.isEmpty()) return Optional.empty();
        if (!(list.get(0) instanceof Map<?, ?> row)) return Optional.empty();
        for (String key : List.of("value", "count", "metric_value", "result", "_count")) {
            Object v = row.get(key);
            if (v instanceof Number n) return Optional.of(n.doubleValue());
        }
        return Optional.empty();
    }
}
