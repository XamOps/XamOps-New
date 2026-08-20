package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindAccountConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UpwindAccountConnectionRepository extends JpaRepository<UpwindAccountConnection, Long> {

    Optional<UpwindAccountConnection> findByCloudAccountId(String cloudAccountId);

    List<UpwindAccountConnection> findByCloudAccountIdIn(List<String> cloudAccountIds);

    /**
     * Connections still awaiting confirmation, for the verifier poll.
     *
     * Deliberately "not CONNECTED" rather than "== PENDING": rows created by the older
     * admin-registered flow sit at NOT_CONNECTED, and those should still be promoted once Upwind
     * starts reporting their assets.
     */
    List<UpwindAccountConnection> findByStatusNot(String status);
}
