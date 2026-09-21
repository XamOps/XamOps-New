package com.xammer.cloud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;

import jakarta.persistence.EnumType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String billingPeriod;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;

    @Column(precision = 19, scale = 4)
    private BigDecimal preDiscountTotal;

    @Column(precision = 19, scale = 4)
    private BigDecimal discountAmount;

    // --- NEW FIELD: Tax Amount ---
    @Column(precision = 19, scale = 4)
    private BigDecimal taxAmount;
    // -----------------------------

    @Column(precision = 19, scale = 4)
    private BigDecimal amount; // Grand Total (Taxable + Tax)

    // AWS Marketplace charge (BILLING_ENTITY = "AWS Marketplace"), in USD.
    // Included in preDiscountTotal but NEVER discounted; shown as its own line.
    @Column(name = "marketplace_charge", precision = 19, scale = 4)
    private BigDecimal marketplaceCharge;

    // Whether this invoice includes IRN (Tax Invoice) or is a Performa Invoice
    @Column(name = "include_irn", nullable = false)
    private boolean includeIrn = false;

    /**
     * Which source the figures were read from. Null on invoices generated before this
     * was recorded, which were all Cost Explorer.
     *
     * Recorded rather than inferred because the two sources legitimately disagree —
     * CUR carries the invoice's own credits, tax and amortisation natively, Cost
     * Explorer approximates them — so anyone questioning a total needs to know which
     * one produced it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", length = 16)
    private DataSource dataSource;

    /**
     * True when built from a month that had not closed. The report rewrites the open
     * month several times a day and AWS restates it after it closes, so a draft like
     * this is an estimate of the bill, not the bill.
     *
     * A wrapper with a column default because billops runs ddl-auto=update: adding a
     * primitive NOT NULL column to a populated invoice table fails, and Hibernate logs
     * that as a warning and starts anyway — leaving every invoice read selecting a
     * column that does not exist.
     */
    @Column(name = "provisional", columnDefinition = "boolean default false")
    private Boolean provisional = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Discount> discounts = new ArrayList<>();

    public enum InvoiceStatus {
        DRAFT,
        FINALIZED,
        VOID
    }

    /** Where an invoice's cost figures came from. */
    public enum DataSource {
        /** The customer's Cost and Usage Report, read through Athena. */
        CUR,
        /** The Cost Explorer API — only ever on an admin's explicit request. */
        COST_EXPLORER
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public BigDecimal getPreDiscountTotal() { return preDiscountTotal; }
    public void setPreDiscountTotal(BigDecimal preDiscountTotal) { this.preDiscountTotal = preDiscountTotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    
    // --- NEW GETTER/SETTER ---
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    // -------------------------

    public BigDecimal getAmount() { return this.amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getMarketplaceCharge() { return marketplaceCharge; }
    public void setMarketplaceCharge(BigDecimal marketplaceCharge) { this.marketplaceCharge = marketplaceCharge; }
    public boolean isIncludeIrn() { return includeIrn; }
    public void setIncludeIrn(boolean includeIrn) { this.includeIrn = includeIrn; }
    public DataSource getDataSource() { return dataSource; }
    public void setDataSource(DataSource dataSource) { this.dataSource = dataSource; }
    public boolean isProvisional() { return Boolean.TRUE.equals(provisional); }
    public void setProvisional(boolean provisional) { this.provisional = provisional; }
    public CloudAccount getCloudAccount() { return cloudAccount; }
    public void setCloudAccount(CloudAccount cloudAccount) { this.cloudAccount = cloudAccount; }
    public List<InvoiceLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItem> lineItems) { this.lineItems = lineItems; }
    public List<Discount> getDiscounts() { return discounts; }
    public void setDiscounts(List<Discount> discounts) { this.discounts = discounts; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}