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

    /**
     * Finds all users created by a specific user.
     * 
     * @param createdBy The ID of the user who created them.
     * @return A list of User entities.
     */
    List<User> findByCreatedBy(Long createdBy);

    List<User> findByClientId(Long clientId);
}
