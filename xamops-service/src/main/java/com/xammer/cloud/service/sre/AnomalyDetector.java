package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SloDefinition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);
    private static final double ALPHA = 0.1;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnomalyDetector(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record AnomalyResult(double zScore, double baseline) {}

    public AnomalyResult detect(SloDefinition slo, double metricValue) {
        String key = "ewma:" + slo.getClientId() + ":" + slo.getEntity() + ":" + slo.getSignal();
        EwmaState state = loadState(key);

        double priorMean = state.mean;
        double zScore = 0.0;
        if (state.count > 0 && state.variance > 1e-9) {
            zScore = (metricValue - priorMean) / Math.sqrt(state.variance);
        }

        double diff = metricValue - priorMean;
        state.mean = priorMean + ALPHA * diff;
        state.variance = (1 - ALPHA) * (state.variance + ALPHA * diff * diff);
        state.count++;
        saveState(key, state);

        return new AnomalyResult(zScore, priorMean);
    }

    private EwmaState loadState(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) return objectMapper.readValue(json, EwmaState.class);
        } catch (Exception e) {
            log.warn("Could not load EWMA state for key {}: {}", key, e.getMessage());
        }
        return new EwmaState(0.0, 1.0, 0);
    }

    private void saveState(String key, EwmaState state) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(state));
        } catch (Exception e) {
            log.warn("Could not save EWMA state for key {}: {}", key, e.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class EwmaState {
        double mean;
        double variance;
        long count;
    }
}
