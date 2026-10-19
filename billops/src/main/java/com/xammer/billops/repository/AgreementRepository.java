package com.xammer.billops.repository;

import com.xammer.cloud.domain.Agreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    // For Admins: Fetch all agreements for an account
    List<Agreement> findByCloudAccountId(Long cloudAccountId);

    // For Users: Fetch only finalized agreements
    List<Agreement> findByCloudAccountIdAndStatus(Long cloudAccountId, String status);

    /**
     * Deletes every agreement uploaded by the given user. agreement.uploaded_by_user_id is a
     * NOT NULL foreign key, so these rows must be removed before the app_user row can be deleted.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Agreement a WHERE a.uploadedBy.id = :userId")
    void deleteByUploadedByUserId(@Param("userId") Long userId);
}