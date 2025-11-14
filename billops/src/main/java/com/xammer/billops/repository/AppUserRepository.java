package com.xammer.billops.repository;

import com.xammer.cloud.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List; // 1. ADD THIS IMPORT
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // 2. ADD THIS METHOD
    /**
     * Finds all users associated with a specific client ID.
     * @param clientId The ID of the client.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByClientId(Long clientId);
        Optional<AppUser> findByUsername(String username);

}