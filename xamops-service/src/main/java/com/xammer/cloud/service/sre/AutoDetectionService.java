package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DetectionEvent;
import com.xammer.cloud.domain.SloDefinition;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.KafkaDetectionEvent;
import com.xammer.cloud.repository.DetectionEventRepository;
import com.xammer.cloud.repository.ObservabilityConfigRepository;
import com.xammer.cloud.repository.SloDefinitionRepository;
import com.xammer.cloud.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class AutoDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AutoDetectionService.class);
    private static final long DEDUP_BUCKET_SECONDS = 30;

    private final SloDefinitionRepository sloRepo;
    private final DetectionEventRepository detectionRepo;
    private final MetricPoller metricPoller;
    private final AnomalyDetector anomalyDetector;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SreInvestigationService investigationService;
    private final OpenObserveService openObserveService;
    private final ObservabilityConfigRepository obsConfigRepo;
    private final String kafkaTopic;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AutoDetectionService(SloDefinitionRepository sloRepo,
                                DetectionEventRepository detectionRepo,
                                MetricPoller metricPoller,
                                AnomalyDetector anomalyDetector,
                                StringRedisTemplate redis,
                                @Qualifier("sreKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                                SreInvestigationService investigationService,
                                OpenObserveService openObserveService,
                                ObservabilityConfigRepository obsConfigRepo,
                                @Value("${sre.kafka.topic:sre.detection.events}") String kafkaTopic) {
        this.sloRepo = sloRepo;
        this.detectionRepo = detectionRepo;
        this.metricPoller = metricPoller;
        this.anomalyDetector = anomalyDetector;
        this.redis = redis;
        this.kafkaTemplate = kafkaTemplate;
        this.investigationService = investigationService;
        this.openObserveService = openObserveService;
        this.obsConfigRepo = obsConfigRepo;
        this.kafkaTopic = kafkaTopic;
    }

    public void runCycle() {
        List<SloDefinition> definitions = sloRepo.findByEnabledTrue();
        for (SloDefinition slo : definitions) {
            try {
                processSlo(slo);
            } catch (Exception e) {
                log.error("Detection error for slo={} entity={}: {}", slo.getId(), slo.getEntity(), e.getMessage(), e);
            }
        }
    }

    private void processSlo(SloDefinition slo) {
        Optional<Double> metricOpt = metricPoller.poll(slo);
        if (metricOpt.isEmpty()) return;

        double metric = metricOpt.get();
        AnomalyDetector.AnomalyResult anomaly = anomalyDetector.detect(slo, metric);
        if (anomaly.zScore() < slo.getZscoreThreshold()) return;

        String cooldownKey = "cooldown:" + slo.getClientId() + ":" + slo.getEntity() + ":" + slo.getSignal();
        Boolean newCooldown = redis.opsForValue().setIfAbsent(cooldownKey, "1", Duration.ofSeconds(slo.getCooldownSeconds()));
        if (!Boolean.TRUE.equals(newCooldown)) return;

        long bucket = System.currentTimeMillis() / (DEDUP_BUCKET_SECONDS * 1000);
        String dedupKey = "dedup:" + slo.getEntity() + ":" + slo.getSignal() + ":" + bucket;
        Boolean newDedup = redis.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofSeconds(DEDUP_BUCKET_SECONDS));
        if (!Boolean.TRUE.equals(newDedup)) return;

        String evidenceLogs = collectEvidenceLogs(slo);

        DetectionEvent event = new DetectionEvent();
        event.setClientId(slo.getClientId());
        event.setSloDefinitionId(slo.getId());
        event.setEntity(slo.getEntity());
        event.setSignal(slo.getSignal());
        event.setScore(anomaly.zScore());
        event.setMetricValue(metric);
        event.setMetricBaseline(anomaly.baseline());
        event.setEvidenceLogs(evidenceLogs);
        DetectionEvent saved = detectionRepo.save(event);

        String incidentId = "inc-" + slo.getClientId() + "-" + slo.getEntity() + "-" + System.currentTimeMillis();
        SreInvestigation inv = investigationService.createAutoInvestigation(
            slo.getClientId(), slo, incidentId);

        saved.setInvestigationId(inv.getId());
        detectionRepo.save(saved);

        publishKafkaEvent(slo, anomaly, metric, incidentId, inv.getId(), evidenceLogs);
    }

    private String collectEvidenceLogs(SloDefinition slo) {
        try {
            var obsConfig = obsConfigRepo.findByClientId(slo.getClientId()).orElse(null);
            if (obsConfig == null) return "";
            long nowSec = System.currentTimeMillis() / 1000;
            Map<String, Object> params = new HashMap<>();
            params.put("providerAccountId", obsConfig.getO2OrgId());
            params.put("stream", slo.getStreamName());
            params.put("keyword", slo.getEntity());
            params.put("startTime", nowSec - 300);
            params.put("endTime", nowSec);
            params.put("size", 50);
            Map<String, Object> result = openObserveService.searchLogs(slo.getClientId(), params);
            Object hits = result.get("hits");
            if (!(hits instanceof List<?> list)) return "";
            List<String> lines = new ArrayList<>();
            for (Object hit : list) {
                if (hit instanceof Map<?, ?> row) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedRow = (Map<String, Object>) row;
                    Object msg = typedRow.getOrDefault("message", typedRow.getOrDefault("log", ""));
                    lines.add(String.valueOf(msg));
                    if (lines.size() >= 50) break;
                }
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("Could not collect evidence logs for {}: {}", slo.getEntity(), e.getMessage());
            return "";
        }
    }

    private void publishKafkaEvent(SloDefinition slo, AnomalyDetector.AnomalyResult anomaly,
                                   double metric, String incidentId, Long investigationId,
                                   String evidenceLogs) {
        try {
            KafkaDetectionEvent msg = new KafkaDetectionEvent();
            msg.setIncidentId(incidentId);
            msg.setVersion(1);
            msg.setStatus("preliminary");
            msg.setUpdatedAt(System.currentTimeMillis() / 1000);
            msg.setClientId(slo.getClientId());
            msg.setEntity(slo.getEntity());
            msg.setResourceType("GENERIC");
            msg.setSignal(slo.getSignal());
            msg.setScore(anomaly.zScore());
            msg.setMetricSummary(Map.of(
                "metric", slo.getSignal(),
                "baseline", anomaly.baseline(),
                "peak", metric,
                "change_pct", anomaly.baseline() > 0 ? Math.round((metric - anomaly.baseline()) / anomaly.baseline() * 100) : 0
            ));
            if (evidenceLogs != null && !evidenceLogs.isBlank()) {
                msg.setEvidenceLogLines(List.of(evidenceLogs.split("\n", 50)));
            }
            msg.setInvestigationId(investigationId);

            String json = objectMapper.writeValueAsString(msg);
            String partitionKey = slo.getClientId() + ":" + slo.getEntity();
            kafkaTemplate.send(kafkaTopic, partitionKey, json);
        } catch (Exception e) {
            log.error("Failed to publish Kafka event for entity={}: {}", slo.getEntity(), e.getMessage(), e);
        }
    }
}
