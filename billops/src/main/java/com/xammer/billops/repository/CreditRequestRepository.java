package com.xammer.billops.repository;

import com.xammer.billops.domain.CreditRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditRequestRepository extends JpaRepository<CreditRequest, Long> {

    @Query("SELECT cr FROM CreditRequest cr WHERE cr.user.id = :userId ORDER BY cr.submittedDate DESC")
    List<CreditRequest> findByUserId(@Param("userId") Long userId);

    @Query("SELECT cr FROM CreditRequest cr ORDER BY cr.submittedDate DESC")
    List<CreditRequest> findAllOrderBySubmittedDateDesc();
}
