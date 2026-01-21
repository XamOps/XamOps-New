package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UserCloudAccountAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserCloudAccountAccessRepository extends JpaRepository<UserCloudAccountAccess, Long> {

    List<UserCloudAccountAccess> findByUserId(Long userId);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);

    @Query("SELECT uca.cloudAccount.id FROM UserCloudAccountAccess uca WHERE uca.user.id = :userId")
    List<Long> findCloudAccountIdsByUserId(@Param("userId") Long userId);
}
