package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.repository.SreNotificationRepository;
import org.springframework.stereotype.Service;

@Service
public class SreNotificationService {

    private final SreNotificationRepository notificationRepo;

    public SreNotificationService(SreNotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    public void notifyComplete(SreInvestigation inv, Long clientId) {
        SreNotification notif = new SreNotification();
        notif.setClientId(clientId);
        notif.setInvestigationId(inv.getId());
        notif.setTitle("Investigation completed: " + inv.getResourceName());
        notif.setMessage(inv.getReportSummary());
        notificationRepo.save(notif);
    }

    public void notifyFailed(SreInvestigation inv, Long clientId) {
        SreNotification notif = new SreNotification();
        notif.setClientId(clientId);
        notif.setInvestigationId(inv.getId());
        notif.setTitle("Investigation failed: " + inv.getResourceName());
        notif.setMessage(inv.getErrorMessage());
        notificationRepo.save(notif);
    }
}
