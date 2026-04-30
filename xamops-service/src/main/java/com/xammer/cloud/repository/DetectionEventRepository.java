package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {
    List<DetectionEvent> findTop10ByClientIdOrderByDetectedAtDesc(Long clientId);
}
