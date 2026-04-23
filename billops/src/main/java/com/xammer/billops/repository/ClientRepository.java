package com.xammer.billops.repository;

import com.xammer.billops.domain.Client;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
    // Find client by name
    Client findByName(String name);

    // Query to get the maximum client ID for auto-increment
    @Query("SELECT MAX(c.id) FROM Client c")
    Long findMaxId();

    /**
     * Returns clients owned by a specific ROLE_BILLOPS_ADMIN_SUB user.
     * Used to scope the support dashboard for sub-admins.
     */
    List<Client> findByOwnerAdminId(Long ownerAdminId);
}