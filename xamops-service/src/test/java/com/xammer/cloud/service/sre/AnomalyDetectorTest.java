package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SloDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnomalyDetectorTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        detector = new AnomalyDetector(redis);
    }

    @Test
    void firstObservation_zScoreIsZero() {
        when(ops.get(anyString())).thenReturn(null); // no prior state
        SloDefinition slo = slo(42L, "payments", "error_rate");

        AnomalyDetector.AnomalyResult result = detector.detect(slo, 5.0);

        assertThat(result.zScore()).isEqualTo(0.0);
        verify(ops).set(anyString(), anyString()); // state saved
    }

    @Test
    void clearSpike_returnsHighZScore() {
        // Seed a stable baseline: mean=1.0, variance=0.1, count=50
        String stateJson = "{\"mean\":1.0,\"variance\":0.1,\"count\":50}";
        when(ops.get(anyString())).thenReturn(stateJson);
        SloDefinition slo = slo(42L, "payments", "error_rate");

        AnomalyDetector.AnomalyResult result = detector.detect(slo, 10.0);

        assertThat(result.zScore()).isGreaterThan(3.0);
        assertThat(result.baseline()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.5));
    }

    private SloDefinition slo(Long clientId, String entity, String signal) {
        SloDefinition s = new SloDefinition();
        s.setClientId(clientId);
        s.setEntity(entity);
        s.setSignal(signal);
        return s;
    }
}
