package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AutoSpottingSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoSpottingSettingsRepository extends JpaRepository<AutoSpottingSettings, Long> {
    Optional<AutoSpottingSettings> findByAccountId(Long accountId);
    List<AutoSpottingSettings> findAllByEnabled(boolean enabled);

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
