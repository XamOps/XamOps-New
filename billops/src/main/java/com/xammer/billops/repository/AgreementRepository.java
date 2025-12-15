package com.xammer.billops.repository;

import com.xammer.cloud.domain.Agreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    // For Admins: Fetch all agreements for an account
    List<Agreement> findByCloudAccountId(Long cloudAccountId);

    // For Users: Fetch only finalized agreements
    List<Agreement> findByCloudAccountIdAndStatus(Long cloudAccountId, String status);
}