package com.xammer.cloud.service.sre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.KafkaDetectionEvent;
import com.xammer.cloud.repository.SreInvestigationRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class SreDetectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SreDetectionConsumer.class);

    @Value("${sre.detection.max-join-window-seconds:600}")
    private int maxJoinWindowSeconds;

    @Value("${sre.detection.reopen-grace-seconds:120}")
    private int reopenGraceSeconds;

    private final SreInvestigationRepository investigationRepo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SreDetectionConsumer(SreInvestigationRepository investigationRepo,
                                StringRedisTemplate redis,
                                ObjectMapper objectMapper) {
        this.investigationRepo = investigationRepo;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
        topics = "${sre.kafka.topic:sre.detection.events}",
        containerFactory = "sreKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            KafkaDetectionEvent event = objectMapper.readValue(record.value(), KafkaDetectionEvent.class);
            if ("final".equals(event.getStatus())) {
                handleFinalEvent(event);
            } else if ("preliminary".equals(event.getStatus())) {
                handlePreliminaryEvent(event, record.offset());
            }
        } catch (Exception e) {
            log.error("RCA consumer failed on offset={}: {}", record.offset(), e.getMessage(), e);
        } finally {
            ack.acknowledge();
        }
    }

    private void handleFinalEvent(KafkaDetectionEvent event) {
        investigationRepo.findByIncidentId(event.getIncidentId()).ifPresent(inv -> {
            if (event.getVersion() <= inv.getVersion()) return;
            inv.setIncidentStatus(SreInvestigation.IncidentStatus.FINAL);
            inv.setVersion(event.getVersion());
            investigationRepo.save(inv);
            log.info("Incident {} marked FINAL", event.getIncidentId());
        });
    }

    private void handlePreliminaryEvent(KafkaDetectionEvent event, long kafkaOffset) {
        long nowEpoch = System.currentTimeMillis() / 1000;
        String eventsKey = "events:" + event.getEntity();

        redis.opsForZSet().add(eventsKey, event.getIncidentId(), nowEpoch);
        redis.expire(eventsKey, Duration.ofSeconds(maxJoinWindowSeconds * 2L));
        redis.opsForZSet().removeRangeByScore(eventsKey, 0, nowEpoch - maxJoinWindowSeconds);

        String openKey = "incident:open:" + event.getEntity();
        String existingIncidentId = redis.opsForValue().get(openKey);

        boolean attachedToExisting = false;
        if (existingIncidentId != null && !existingIncidentId.equals(event.getIncidentId())) {
            var existingOpt = investigationRepo.findByIncidentId(existingIncidentId);
            if (existingOpt.isPresent() && existingOpt.get().getIncidentStatus() == SreInvestigation.IncidentStatus.OPEN) {
                log.info("Attaching detection to existing incident {} for entity {}",
                    existingIncidentId, event.getEntity());
                if (event.getInvestigationId() != null) {
                    investigationRepo.findById(event.getInvestigationId()).ifPresent(dup -> {
                        dup.setIncidentStatus(SreInvestigation.IncidentStatus.CLOSED);
                        dup.setClosedAt(Instant.now());
                        investigationRepo.save(dup);
                    });
                }
                attachedToExisting = true;
            }
        }

        if (attachedToExisting) return;

        redis.opsForValue().set(openKey, event.getIncidentId(),
            Duration.ofSeconds(maxJoinWindowSeconds));

        if (event.getInvestigationId() != null) {
            investigationRepo.findById(event.getInvestigationId()).ifPresent(inv -> {
                if (inv.getIncidentId() == null) {
                    inv.setIncidentId(event.getIncidentId());
                }
                inv.setIncidentStatus(SreInvestigation.IncidentStatus.OPEN);
                investigationRepo.save(inv);
            });
        }

        log.info("Preliminary event processed: entity={} incidentId={} score={}",
            event.getEntity(), event.getIncidentId(), event.getScore());
    }
}
