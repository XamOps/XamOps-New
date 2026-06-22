package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.UserS3Config;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserS3ConfigRepository extends JpaRepository<UserS3Config, Long> {
    List<UserS3Config> findByClientId(Long clientId);
    Optional<UserS3Config> findByIdAndClientId(Long id, Long clientId);
}
