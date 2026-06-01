package com.xammer.cloud.service;

import com.xammer.cloud.repository.ExplainRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class ExplainCleanupService {
    private static final Logger logger = LoggerFactory.getLogger(ExplainCleanupService.class);
    private final ExplainRequestRepository explainRequestRepository;

    public ExplainCleanupService(ExplainRequestRepository explainRequestRepository) {
        this.explainRequestRepository = explainRequestRepository;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireStaleRequests() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        int expired = explainRequestRepository.expireStaleRequests(cutoff);
        if (expired > 0) {
            logger.info("ExplainCleanup: expired {} stale explain request(s)", expired);
        }
    }
}
