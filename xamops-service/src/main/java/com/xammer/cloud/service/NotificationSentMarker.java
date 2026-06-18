package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdRunRecord;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationSentMarker {

    private final CicdRunRecordRepository runRecordRepo;

    public NotificationSentMarker(CicdRunRecordRepository runRecordRepo) {
        this.runRecordRepo = runRecordRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(CicdRunRecord record) {
        record.setNotificationSent(true);
        runRecordRepo.save(record);
    }
}
