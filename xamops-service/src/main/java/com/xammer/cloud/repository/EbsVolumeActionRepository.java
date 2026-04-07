package com.xammer.cloud.repository;

import com.xammer.cloud.domain.EbsVolumeAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface EbsVolumeActionRepository extends JpaRepository<EbsVolumeAction, Long> {
    List<EbsVolumeAction> findByAccountIdOrderByInitiatedAtDesc(Long accountId);
    List<EbsVolumeAction> findByAccountIdAndVolumeId(Long accountId, String volumeId);

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
