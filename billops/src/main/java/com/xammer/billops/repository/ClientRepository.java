package com.xammer.billops.repository;

import com.xammer.billops.domain.Client;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
    // Find client by name
    Client findByName(String name);

    // Query to get the maximum client ID for auto-increment
    @Query("SELECT MAX(c.id) FROM Client c")
    Long findMaxId();
}