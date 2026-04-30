package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.DetectionEvent;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.SloDefinitionRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoDetectionServiceTest {

    private SloDefinitionRepository sloRepo;
    private DetectionEventRepository detectionRepo;
    private MetricPoller metricPoller;
    private AnomalyDetector anomalyDetector;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private KafkaTemplate<String, String> kafkaTemplate;
    private SreInvestigationService investigationService;
    private OpenObserveService openObserveService;
    private ObservabilityConfigRepository obsConfigRepo;
    private AutoDetectionService service;

    @BeforeEach
    void setUp() {
        sloRepo = mock(SloDefinitionRepository.class);
        detectionRepo = mock(DetectionEventRepository.class);
        metricPoller = mock(MetricPoller.class);
        anomalyDetector = mock(AnomalyDetector.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        investigationService = mock(SreInvestigationService.class);
        openObserveService = mock(OpenObserveService.class);
        obsConfigRepo = mock(ObservabilityConfigRepository.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new AutoDetectionService(sloRepo, detectionRepo, metricPoller,
            anomalyDetector, redis, kafkaTemplate, investigationService,
            openObserveService, obsConfigRepo, "sre.detection.events");
    }

    @Test
    void runCycle_anomalyDetected_publishesToKafka() {
        SloDefinition slo = new SloDefinition();
        slo.setId(1L);
        slo.setClientId(42L);
        slo.setEntity("payments");
        slo.setSignal("error_rate");
        slo.setStreamName("k8s-logs");
        slo.setQueryTemplate("SELECT COUNT(*) as count FROM {stream} WHERE service='{entity}'");
        slo.setZscoreThreshold(3.0);
        slo.setCooldownSeconds(600);

        when(sloRepo.findByEnabledTrue()).thenReturn(List.of(slo));
        when(metricPoller.poll(slo)).thenReturn(Optional.of(12.4));
        when(anomalyDetector.detect(slo, 12.4)).thenReturn(new AnomalyDetector.AnomalyResult(4.2, 0.8));
        when(redis.hasKey(anyString())).thenReturn(false); // no cooldown, no dedup

        DetectionEvent saved = new DetectionEvent();
        saved.setId(10L);
        when(detectionRepo.save(any())).thenReturn(saved);

        SreInvestigation inv = new SreInvestigation();
        inv.setId(101L);
        when(investigationService.createAutoInvestigation(any(), any(), any())).thenReturn(inv);

        service.runCycle();

        verify(kafkaTemplate).send(eq("sre.detection.events"), anyString(), anyString());
        verify(detectionRepo, atLeastOnce()).save(any(DetectionEvent.class));
    }

    @Test
    void runCycle_cooldownActive_doesNotPublish() {
        SloDefinition slo = new SloDefinition();
        slo.setId(1L);
        slo.setClientId(42L);
        slo.setEntity("payments");
        slo.setSignal("error_rate");
        slo.setZscoreThreshold(3.0);
        slo.setCooldownSeconds(600);

        when(sloRepo.findByEnabledTrue()).thenReturn(List.of(slo));
        when(metricPoller.poll(slo)).thenReturn(Optional.of(12.4));
        when(anomalyDetector.detect(slo, 12.4)).thenReturn(new AnomalyDetector.AnomalyResult(4.2, 0.8));
        when(redis.hasKey(contains("cooldown"))).thenReturn(true); // cooldown active

        service.runCycle();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
