package com.xammer.billops.repository;

import com.xammer.billops.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByCloudAccountIdAndBillingPeriodAndStatus(Long cloudAccountId, String billingPeriod, Invoice.InvoiceStatus status);
}