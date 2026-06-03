package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final AtomicBoolean active = new AtomicBoolean(false);

    public void enable() {
        active.set(true);
        log.info("Maintenance mode enabled");
    }

    public void disable() {
        active.set(false);
        log.info("Maintenance mode disabled");
    }

    public boolean isActive() {
        return active.get();
    }
}
