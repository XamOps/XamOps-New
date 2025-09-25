package com.xammer.billops.domain;

import jakarta.persistence.*;
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

    // --- FIX: Reverted 'finalTotal' to 'amount' ---
    @Column(precision = 19, scale = 4)
    private BigDecimal amount;
    // --- END FIX ---

    // --- FIX: Changed FetchType to EAGER to prevent LazyInitializationException ---
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    private CloudAccount cloudAccount;
    // --- END FIX ---

    // --- FIX: Changed FetchType to EAGER ---
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;
    // --- END FIX ---

    // --- FIX: Changed FetchType to EAGER ---
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<InvoiceLineItem> lineItems = new ArrayList<>();
    // --- END FIX ---

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Discount> discounts = new ArrayList<>();

    public enum InvoiceStatus {
        DRAFT,
        FINALIZED
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
    
    // --- FIX: Updated getters and setters for renamed field ---
    public BigDecimal getAmount() { return this.amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    // --- END FIX ---

    public CloudAccount getCloudAccount() { return cloudAccount; }
    public void setCloudAccount(CloudAccount cloudAccount) { this.cloudAccount = cloudAccount; }
    public List<InvoiceLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItem> lineItems) { this.lineItems = lineItems; }
    public List<Discount> getDiscounts() { return discounts; }
    public void setDiscounts(List<Discount> discounts) { this.discounts = discounts; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}