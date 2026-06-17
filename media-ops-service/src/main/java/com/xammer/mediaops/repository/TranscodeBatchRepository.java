package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.TranscodeBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TranscodeBatchRepository extends JpaRepository<TranscodeBatch, Long> {

    List<TranscodeBatch> findByClientIdOrderByCreatedAtDesc(Long clientId);
}
