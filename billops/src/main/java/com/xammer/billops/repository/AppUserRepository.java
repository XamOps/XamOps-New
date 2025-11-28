package com.xammer.billops.repository;

import com.xammer.cloud.domain.AppUser; // ✅ Updated Import
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Finds all users associated with a specific client ID.
     * @param clientId The ID of the client.
     * @return A list of AppUser entities.
     */
    List<AppUser> findByClientId(Long clientId);
    
    Optional<AppUser> findByUsername(String username);

}