package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DevOpsScript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DevOpsScriptRepository extends JpaRepository<DevOpsScript, Long> {
    // Spring Data JPA auto-generates find, save, delete, etc.
}