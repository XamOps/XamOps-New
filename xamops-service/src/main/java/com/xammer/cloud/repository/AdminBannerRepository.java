package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AdminBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminBannerRepository extends JpaRepository<AdminBanner, Long> {

    List<AdminBanner> findAllByOrderByCreatedAtDesc();

    List<AdminBanner> findByCreatedByUserIdOrderByCreatedAtDesc(Long createdByUserId);

    /** Banners a client should see: everything sent to all clients, plus those naming it. */
    @Query("SELECT DISTINCT b FROM AdminBanner b LEFT JOIN b.targetClientIds t "
            + "WHERE b.targetAll = true OR t = :clientId "
            + "ORDER BY b.createdAt DESC")
    List<AdminBanner> findVisibleToClient(@Param("clientId") Long clientId);
}
