package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.TranscodePreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TranscodePresetRepository extends JpaRepository<TranscodePreset, Long> {

    List<TranscodePreset> findByClientIdOrderByCreatedAtDesc(Long clientId);
}
