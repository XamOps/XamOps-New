package com.xammer.billops.repository;

import com.xammer.billops.domain.Discount; // Assuming this domain class exists
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountRepository extends JpaRepository<Discount, Long> {
    // You can add custom query methods here later
}