package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardUpdateService.class);

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public DashboardUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Sends a WebSocket update to a specific destination.
     *
     * @param destination The WebSocket topic (e.g., /topic/dashboard/{accountId}/billing).
     * @param payload     The data to be sent.
     */
    public void sendUpdate(String destination, Object payload) {
        // *** THIS IS THE FIX: Check if the payload is null before sending. ***
        if (payload == null) {
            logger.warn("Payload for destination '{}' is null. Skipping WebSocket update.", destination);
            return; // Do not send a message if the payload is null
        }

        try {
            logger.info("Sending WebSocket update to destination: {}", destination);
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            logger.error("Failed to send WebSocket update to {}", destination, e);
        }
    }
}