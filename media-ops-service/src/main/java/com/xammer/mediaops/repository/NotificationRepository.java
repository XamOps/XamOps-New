package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByClientIdOrderByCreatedAtDesc(Long clientId);

    int countByClientIdAndReadFalse(Long clientId);

    Optional<Notification> findByIdAndClientId(Long id, Long clientId);
}
