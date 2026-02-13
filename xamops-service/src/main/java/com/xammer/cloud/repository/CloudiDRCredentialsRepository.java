package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudiDRCredentials;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CloudiDRCredentialsRepository extends JpaRepository<CloudiDRCredentials, Long> {
    Optional<CloudiDRCredentials> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
