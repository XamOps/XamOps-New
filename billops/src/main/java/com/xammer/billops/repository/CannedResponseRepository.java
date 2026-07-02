package com.xammer.billops.repository;

import com.xammer.cloud.domain.CannedResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CannedResponseRepository extends JpaRepository<CannedResponse, Long> {
    List<CannedResponse> findAllByOrderByTitleAsc();
}
