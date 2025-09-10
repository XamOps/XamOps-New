package com.xammer.billops.repository;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {
    List<CloudAccount> findByCustomerId(Long customerId);
    Optional<CloudAccount> findByExternalId(String externalId);
}