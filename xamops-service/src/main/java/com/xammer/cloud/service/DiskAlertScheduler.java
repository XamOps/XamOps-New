package com.xammer.cloud.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiskAlertScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DiskAlertScheduler.class);

    private final DiskAlertService diskAlertService;

    @Scheduled(cron = "0 0 */4 * * *") // every 4 hours
    public void runDiskAlertScan() {
        logger.info("[DiskAlertScheduler] Starting disk utilisation alert scan");
        diskAlertService.runAlertScan();
        logger.info("[DiskAlertScheduler] Disk alert scan complete");
    }
}
