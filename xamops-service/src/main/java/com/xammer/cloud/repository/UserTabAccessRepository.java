package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UserTabAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserTabAccessRepository extends JpaRepository<UserTabAccess, Long> {

    @Query("SELECT uta.tabName FROM UserTabAccess uta WHERE uta.user.id = :userId")
    List<String> findTabNamesByUserId(@Param("userId") Long userId);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
