package com.xammer.billops.repository;

import com.xammer.cloud.domain.AppUser; // ✅ Updated Import
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @Modifying
    @Query("DELETE FROM AppUser u WHERE u.id = :id")
    void deleteByIdDirect(@Param("id") Long id);

    /**
     * Finds all users associated with a specific client ID.
     * @param clientId The ID of the client.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByClientId(Long clientId);

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    /**
     * Finds all users created by a specific user.
     * @param createdBy The ID of the user who created them.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByCreatedBy(Long createdBy);

}