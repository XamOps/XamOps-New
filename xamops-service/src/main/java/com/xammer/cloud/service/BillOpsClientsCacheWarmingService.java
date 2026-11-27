package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.dto.billops.BillOpsClientDetailsDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.service.billops.BillOpsClientListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BillOpsClientsCacheWarmingService {

    private static final Logger log = LoggerFactory.getLogger(BillOpsClientsCacheWarmingService.class);

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    /** The builder the controller uses, so the copy cached here holds every field the page reads. */
    @Autowired
    private BillOpsClientListService clientListService;

    // Warm the cache on startup
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        log.info("🔄 Warming BillOps clients cache on startup...");
        warmClientsCache();
    }

    // Periodically refresh the cache every 4 minutes (before 5-minute TTL expires)
    @Scheduled(fixedDelay = 240000) // 4 minutes
    public void refreshClientsCache() {
        log.info("🔄 Refreshing BillOps clients cache (scheduled)...");
        warmClientsCache();
    }

    /**
     * Warm the clients cache by building and storing the full client details.
     * This runs in the background so dashboard loads show cached data immediately.
     *
     * Database reads only: spend the DB cache does not hold yet, and AWS credits, are cached as 0
     * rather than fetched, which keeps startup fast and keeps a 4-minute job off the cloud APIs.
     */
    private void warmClientsCache() {
        try {
            List<Client> clients = clientRepository.findAll();
            if (clients.isEmpty()) {
                log.info("No clients found to cache");
                return;
            }

            List<BillOpsClientDetailsDto> clientDtos = clientListService.buildFromCacheOnly(clients);
            redisCacheService.put(BillOpsClientListService.CLIENTS_CACHE_KEY, clientDtos,
                    BillOpsClientListService.CLIENTS_CACHE_TTL_MINUTES);
            log.info("✅ BillOps clients cache warmed with {} clients", clientDtos.size());
        } catch (Exception e) {
            log.error("❌ Failed to warm BillOps clients cache", e);
        }
    }
}
