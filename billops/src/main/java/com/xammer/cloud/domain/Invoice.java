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

    /**
     * Percentage markup applied only to {@link #marketplaceCharge}, set by the admin per
     * invoice. Null/absent and zero both mean "no markup": the invoice renders and totals
     * exactly as it did before this field existed. Stored in percent units (15.00 = 15%).
     *
     * <p>Nullable {@code BigDecimal} rather than a NOT NULL primitive so {@code
     * ddl-auto=update} can add it to the populated invoice table without failing, and so
     * every pre-existing invoice reads back null -- identical to 0%. Mirrors the
     * nullability reasoning already documented on {@link #exchangeRate} and
     * {@link #privatePricedTotal}.
     */
    @Column(name = "marketplace_holding_cost_percent", precision = 5, scale = 2)
    private BigDecimal marketplaceHoldingCostPercent;

    /**
     * How much of {@link #preDiscountTotal} is billed at a rate an admin set rather than
     * at provider cost.
     *
     * <p>Stored alongside the other totals, by the same method that derives them, so a
     * finalized invoice keeps the figures it was finalized with. Nullable on every invoice
     * priced entirely at provider cost, which is almost all of them.
     */
    @Column(name = "private_priced_total", precision = 19, scale = 4)
    private BigDecimal privatePricedTotal;

    /**
     * The part of {@link #privatePricedTotal} that an invoice-wide or consumption-wide
     * discount may not reduce, because the rate's own shield is on.
     *
     * <p>Always less than or equal to {@code privatePricedTotal}: an admin can price
     * charges and still leave the overall discount free to reach them.
     */
    @Column(name = "discount_shielded_total", precision = 19, scale = 4)
    private BigDecimal discountShieldedTotal;

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

    /**
     * How many INR equal 1 USD this invoice finalized at. Null on invoices finalized
     * before this existed, which fall back to the CloudAccount / live / hardcoded rate.
     *
     * <p>Nullable {@code Double} (mirroring {@link CloudAccount#usdExchangeRate}) rather
     * than a NOT NULL primitive so {@code ddl-auto=update} can add it to the populated
     * invoice table without failing, and so old invoices read back {@code null}.
     */
    @Column(name = "exchange_rate")
    private Double exchangeRate;

    /**
     * File name of the AWS bill PDF this draft was created from, when an admin uploaded one
     * instead of generating it. Null on every generated invoice.
     *
     * <p>Its own nullable column rather than a third {@link DataSource} value: PostgreSQL
     * tables Hibernate created carry a check constraint listing the enum's values, and
     * {@code ddl-auto=update} never widens it, so a new value would fail every insert.
     */
    @Column(name = "uploaded_file_name")
    private String uploadedFileName;

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

    /**
     * Rates an admin set by hand for parts of this invoice, replacing provider cost with
     * {@code quantity x rate}.
     *
     * <p>Cascaded and orphan-removed like discounts, so deleting a draft takes its pricing
     * with it. The amounts themselves live on the line items; these records are the scope,
     * the rate, and whether the charges are shielded from the invoice's overall discount.
     */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<InvoicePrivatePricing> privatePricings = new ArrayList<>();

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
    public BigDecimal getMarketplaceHoldingCostPercent() { return marketplaceHoldingCostPercent; }
    public void setMarketplaceHoldingCostPercent(BigDecimal marketplaceHoldingCostPercent) { this.marketplaceHoldingCostPercent = marketplaceHoldingCostPercent; }
    public BigDecimal getPrivatePricedTotal() { return privatePricedTotal; }
    public void setPrivatePricedTotal(BigDecimal privatePricedTotal) { this.privatePricedTotal = privatePricedTotal; }
    public BigDecimal getDiscountShieldedTotal() { return discountShieldedTotal; }
    public void setDiscountShieldedTotal(BigDecimal discountShieldedTotal) { this.discountShieldedTotal = discountShieldedTotal; }
    public boolean isIncludeIrn() { return includeIrn; }
    public void setIncludeIrn(boolean includeIrn) { this.includeIrn = includeIrn; }
    public DataSource getDataSource() { return dataSource; }
    public void setDataSource(DataSource dataSource) { this.dataSource = dataSource; }
    public boolean isProvisional() { return Boolean.TRUE.equals(provisional); }
    public void setProvisional(boolean provisional) { this.provisional = provisional; }
    public Double getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(Double exchangeRate) { this.exchangeRate = exchangeRate; }
    public String getUploadedFileName() { return uploadedFileName; }
    public void setUploadedFileName(String uploadedFileName) { this.uploadedFileName = uploadedFileName; }
    public CloudAccount getCloudAccount() { return cloudAccount; }
    public void setCloudAccount(CloudAccount cloudAccount) { this.cloudAccount = cloudAccount; }
    public List<InvoiceLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<InvoiceLineItem> lineItems) { this.lineItems = lineItems; }
    public List<Discount> getDiscounts() { return discounts; }
    public void setDiscounts(List<Discount> discounts) { this.discounts = discounts; }
    public List<InvoicePrivatePricing> getPrivatePricings() { return privatePricings; }
    public void setPrivatePricings(List<InvoicePrivatePricing> privatePricings) { this.privatePricings = privatePricings; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
}