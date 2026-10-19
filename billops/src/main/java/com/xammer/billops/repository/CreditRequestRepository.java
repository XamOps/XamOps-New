package com.xammer.billops.repository;

import com.xammer.cloud.domain.CreditRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface CreditRequestRepository extends JpaRepository<CreditRequest, Long> {

    @Query("SELECT cr FROM CreditRequest cr WHERE cr.user.id = :userId ORDER BY cr.submittedDate DESC")
    List<CreditRequest> findByUserId(@Param("userId") Long userId);

    /**
     * Deletes every credit request raised by the given user. credit_request.user_id is a
     * NOT NULL foreign key, so these rows cannot be detached — they must be removed before
     * the app_user row can be deleted.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM CreditRequest cr WHERE cr.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Query("SELECT cr FROM CreditRequest cr ORDER BY cr.submittedDate DESC")
    List<CreditRequest> findAllOrderBySubmittedDateDesc();
}
