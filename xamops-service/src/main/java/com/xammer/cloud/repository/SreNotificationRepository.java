package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SreNotificationRepository extends JpaRepository<SreNotification, Long> {

    List<SreNotification> findByClientIdAndReadFalseOrderByCreatedAtDesc(Long clientId);

    long countByClientIdAndReadFalse(Long clientId);

    List<SreNotification> findByClientIdOrderByCreatedAtDesc(Long clientId);
}
