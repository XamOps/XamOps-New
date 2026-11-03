package com.xammer.billops.repository;

import com.xammer.billops.domain.GlobalTabRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlobalTabRuleRepository extends JpaRepository<GlobalTabRule, Long> {

    Optional<GlobalTabRule> findByTabKey(String tabKey);

    /** Every rule with its exceptions and their users, in one query, for the admin list. */
    @Query("SELECT DISTINCT r FROM GlobalTabRule r "
            + "LEFT JOIN FETCH r.exceptions e LEFT JOIN FETCH e.user u LEFT JOIN FETCH u.client "
            + "ORDER BY r.tabKey")
    List<GlobalTabRule> findAllWithExceptions();
}
