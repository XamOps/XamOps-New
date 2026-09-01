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
    public CloudAccount getCloudAccount() { return cloudAccount; }
    public void setCloudAccount(CloudAccount cloudAccount) { this.cloudAccount = cloudAccount; }
    public List<InvoiceLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItem> lineItems) { this.lineItems = lineItems; }
    public List<Discount> getDiscounts() { return discounts; }
    public void setDiscounts(List<Discount> discounts) { this.discounts = discounts; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}