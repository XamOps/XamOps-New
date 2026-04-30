package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.DetectionEvent;
import java.time.Instant;

public record DetectionEventDto(
    Long id,
    String entity,
    String signal,
    double score,
    Double metricValue,
    Double metricBaseline,
    Long investigationId,
    Instant detectedAt
) {
    public static DetectionEventDto from(DetectionEvent e) {
        return new DetectionEventDto(
            e.getId(), e.getEntity(), e.getSignal(), e.getScore(),
            e.getMetricValue(), e.getMetricBaseline(),
            e.getInvestigationId(), e.getDetectedAt()
        );
    }
}
