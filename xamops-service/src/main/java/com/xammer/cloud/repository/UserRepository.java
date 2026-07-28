package com.xammer.cloud.repository;

import com.xammer.cloud.domain.User;

import io.lettuce.core.dynamic.annotation.Param;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("SELECT u FROM User u JOIN FETCH u.client WHERE u.username = :username")
    Optional<User> findByUsername(@Param("username") String username);

    @Query("SELECT u FROM User u JOIN FETCH u.client WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("SELECT u FROM User u JOIN FETCH u.client WHERE u.googleSub = :googleSub")
    Optional<User> findByGoogleSub(@Param("googleSub") String googleSub);

    @Query("SELECT u FROM User u JOIN FETCH u.client WHERE u.microsoftSub = :microsoftSub")
    Optional<User> findByMicrosoftSub(@Param("microsoftSub") String microsoftSub);

    /**
     * Finds all users created by a specific user.
     * 
     * @param createdBy The ID of the user who created them.
     * @return A list of User entities.
     */
    List<User> findByCreatedBy(Long createdBy);

    List<User> findByClientId(Long clientId);

    List<User> findByClientIdIn(List<Long> clientIds);

    /**
     * Users whose role contains the given fragment. Passing "BILLOPS_ADMIN" matches
     * both ROLE_BILLOPS_ADMIN and ROLE_BILLOPS_ADMIN_SUB, and tolerates rows stored
     * without the ROLE_ prefix.
     */
    List<User> findByRoleContainingIgnoreCase(String roleFragment);
}
