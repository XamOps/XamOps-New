package com.xammer.billops.repository;

import com.xammer.billops.domain.GlobalTabRuleException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface GlobalTabRuleExceptionRepository extends JpaRepository<GlobalTabRuleException, Long> {

    /** Releases a user's exception rows so the user itself can be deleted. */
    @Modifying
    @Transactional
    @Query("DELETE FROM GlobalTabRuleException e WHERE e.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
