package com.xammer.billops.repository;

import com.xammer.billops.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List; // MODIFIED: Import List
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    // --- FIX: Changed return type from Optional<Invoice> to List<Invoice> ---
    List<Invoice> findByCloudAccountIdAndBillingPeriodAndStatus(Long cloudAccountId, String billingPeriod, Invoice.InvoiceStatus status);
    // --- END FIX ---
}