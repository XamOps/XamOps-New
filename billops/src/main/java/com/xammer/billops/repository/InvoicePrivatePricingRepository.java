package com.xammer.billops.repository;

import com.xammer.cloud.domain.InvoicePrivatePricing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoicePrivatePricingRepository extends JpaRepository<InvoicePrivatePricing, Long> {

    /** The private rates an admin has applied to one invoice. */
    List<InvoicePrivatePricing> findByInvoiceId(Long invoiceId);
}
