package com.xammer.billops.repository;

import com.xammer.cloud.domain.Invoice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List; // MODIFIED: Import List
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    // --- FIX: Changed return type from Optional<Invoice> to List<Invoice> ---
    List<Invoice> findByCloudAccountIdAndBillingPeriodAndStatus(Long cloudAccountId, String billingPeriod,
            Invoice.InvoiceStatus status);

    List<Invoice> findByCloudAccountIdAndBillingPeriod(Long cloudAccountId, String billingPeriod);

    // Fix N+1: fetch last invoice date per account without loading all invoices
    Optional<Invoice> findTop1ByCloudAccountIdOrderByInvoiceDateDesc(Long cloudAccountId);

    // Admin dashboard "recent invoices": the page with its account and client loaded, so the DTO
    // conversion never touches a lazy proxy. Both joins are to-one, so paging stays in SQL.
    @Transactional(readOnly = true)
    @Query("select i from Invoice i left join fetch i.cloudAccount left join fetch i.client")
    List<Invoice> findRecentWithAccountAndClient(Pageable page);
    // --- END FIX ---
}