package com.xammer.billops.repository;

import com.xammer.billops.domain.Customer;
import com.xammer.billops.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByUser(User user);

    // --- NEW METHOD ADDED ---
    // This query fetches the Customer and eagerly loads their associated CloudAccounts
    @Query("SELECT c FROM Customer c LEFT JOIN FETCH c.cloudAccounts WHERE c.user = :user")
    Optional<Customer> findByUserWithCloudAccounts(@Param("user") User user);
}