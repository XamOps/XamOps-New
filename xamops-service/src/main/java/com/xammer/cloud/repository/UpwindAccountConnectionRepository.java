package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindAccountConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UpwindAccountConnectionRepository extends JpaRepository<UpwindAccountConnection, Long> {

    Optional<UpwindAccountConnection> findByCloudAccountId(String cloudAccountId);

    List<UpwindAccountConnection> findByCloudAccountIdIn(List<String> cloudAccountIds);
}
