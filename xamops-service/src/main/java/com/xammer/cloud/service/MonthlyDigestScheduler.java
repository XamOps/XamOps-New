package com.xammer.cloud.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MonthlyDigestScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyDigestScheduler.class);

    private final MonthlyDigestService monthlyDigestService;

    @Scheduled(cron = "0 30 12 30 * ?") // 6:00 PM IST on the 30th of each month
    public void runMonthlyDigest() {
        logger.info("[MonthlyDigestScheduler] Triggering monthly cloud digest");
        monthlyDigestService.runDigest();
        logger.info("[MonthlyDigestScheduler] Monthly cloud digest complete");
    }
}
