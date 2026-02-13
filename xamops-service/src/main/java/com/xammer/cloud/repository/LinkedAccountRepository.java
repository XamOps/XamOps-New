package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LinkedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {

    /**
     * Find all linked accounts for a specific user
     */
    List<LinkedAccount> findByUserId(Long userId);

    /**
     * Find a specific linked account by user ID and account ID
     */
    Optional<LinkedAccount> findByUserIdAndAccountId(Long userId, String accountId);

    /**
     * Delete all linked accounts for a user
     */
    void deleteByUserId(Long userId);

    /**
     * Check if a user has any linked accounts
     */
    boolean existsByUserId(Long userId);
}
