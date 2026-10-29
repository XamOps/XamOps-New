package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.xammer.cloud.domain.Discount;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;
import com.xammer.cloud.domain.InvoicePrivatePricing;

public class InvoiceDto {

    private Long id;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String billingPeriod;
    private String status;
    private Long clientId;
    private String clientName;
    private Long cloudAccountId;
    private String accountName;
    private String provider;
    private String awsAccountId;
    private String gcpProjectId;
    private String azureSubscriptionId; // ✅ Added
    private BigDecimal preDiscountTotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal amount;
    private BigDecimal marketplaceCharge;
    /** Editable % markup the admin applies only to {@link #marketplaceCharge}; null/0 = none. */
    private BigDecimal marketplaceHoldingCostPercent;
    /** How much of {@link #preDiscountTotal} is billed at a rate an admin set by hand. */
    private BigDecimal privatePricedTotal;
    /** The part of that which an invoice-wide or consumption-wide discount may not reduce. */
    private BigDecimal discountShieldedTotal;
    private boolean includeIrn;
    /** "CUR" or "COST_EXPLORER"; null for invoices generated before the source was recorded. */
    private String dataSource;
    /** True when built from a month that had not yet closed — an estimate, not the final bill. */
    private boolean provisional;
    /** INR per 1 USD this invoice was finalized at; null until finalized with a chosen rate. */
    private Double exchangeRate;
    /** File name of the AWS bill PDF the draft was created from; null when generated. */
    private String uploadedFileName;
    private long lastUpdated;
    private List<LineItemDto> lineItems;
    private List<DiscountDto> discounts;
    /** Rates an admin applied by hand to parts of this invoice. */
    private List<PrivatePricingDto> privatePricing;

    // Static factory method
    public static InvoiceDto fromEntity(Invoice invoice) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate());
        dto.setBillingPeriod(invoice.getBillingPeriod());
        dto.setStatus(invoice.getStatus() != null ? invoice.getStatus().name() : "DRAFT");

        // Client info
        if (invoice.getClient() != null) {
            dto.setClientId(invoice.getClient().getId());
            dto.setClientName(invoice.getClient().getName());
        }

        // Cloud account info
        try {
            if (invoice.getCloudAccount() != null) {
                dto.setCloudAccountId(invoice.getCloudAccount().getId());
                dto.setAccountName(invoice.getCloudAccount().getAccountName());
                dto.setProvider(invoice.getCloudAccount().getProvider());
                dto.setAwsAccountId(invoice.getCloudAccount().getAwsAccountId());
                dto.setGcpProjectId(invoice.getCloudAccount().getGcpProjectId());
                dto.setAzureSubscriptionId(invoice.getCloudAccount().getAzureSubscriptionId()); // ✅ Map Azure ID
            }
        } catch (jakarta.persistence.EntityNotFoundException e) {
            dto.setAccountName("Unknown/Deleted Account");
            dto.setAwsAccountId("N/A");
            dto.setGcpProjectId("N/A");
            dto.setAzureSubscriptionId("N/A");
        }

        dto.setPreDiscountTotal(invoice.getPreDiscountTotal());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setTaxAmount(invoice.getTaxAmount());
        dto.setAmount(invoice.getAmount());
        dto.setMarketplaceCharge(invoice.getMarketplaceCharge());
        dto.setMarketplaceHoldingCostPercent(invoice.getMarketplaceHoldingCostPercent());
        dto.setPrivatePricedTotal(invoice.getPrivatePricedTotal());
        dto.setDiscountShieldedTotal(invoice.getDiscountShieldedTotal());
        dto.setIncludeIrn(invoice.isIncludeIrn());
        dto.setDataSource(invoice.getDataSource() != null ? invoice.getDataSource().name() : null);
        dto.setProvisional(invoice.isProvisional());
        dto.setExchangeRate(invoice.getExchangeRate());
        dto.setUploadedFileName(invoice.getUploadedFileName());

        // Convert line items (only non-hidden items)
        List<LineItemDto> items = Optional.ofNullable(invoice.getLineItems())
                .orElse(Collections.emptyList())
                .stream()
                .filter(item -> !item.isHidden())
                .map(LineItemDto::fromEntity)
                .collect(Collectors.toList());
        dto.setLineItems(items);

        // Convert discounts
        List<DiscountDto> discountDtos = Optional.ofNullable(invoice.getDiscounts())
                .orElse(Collections.emptyList())
                .stream()
                .map(DiscountDto::fromEntity)
                .collect(Collectors.toList());
        dto.setDiscounts(discountDtos);

        // Manual private pricing
        List<InvoicePrivatePricing> pricings = Optional.ofNullable(invoice.getPrivatePricings())
                .orElse(Collections.emptyList());
        dto.setPrivatePricing(pricings.stream()
                .map(PrivatePricingDto::fromEntity)
                .collect(Collectors.toList()));

        // Which lines an overall discount must skip, resolved here so the invoice PDF and
        // the admin page both read one answer instead of each re-deriving it from the
        // scope records. A line qualifies only if a rate actually priced it: the credits
        // and unpriceable lines inside a scope are still at provider cost, so there is no
        // negotiated margin for a discount to erode.
        List<InvoicePrivatePricing> shields = pricings.stream()
                .filter(InvoicePrivatePricing::isExcludeFromOverallDiscount)
                .collect(Collectors.toList());
        if (!shields.isEmpty()) {
            for (LineItemDto item : items) {
                item.setDiscountShielded(item.isPrivatelyPriced() && shields.stream()
                        .anyMatch(p -> p.matches(item.getId(), item.getServiceName(), item.getRegionName(),
                                item.getUsageType())));
            }
        }

        return dto;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getBillingPeriod() {
        return billingPeriod;
    }

    public void setBillingPeriod(String billingPeriod) {
        this.billingPeriod = billingPeriod;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Long getCloudAccountId() {
        return cloudAccountId;
    }

    public void setCloudAccountId(Long cloudAccountId) {
        this.cloudAccountId = cloudAccountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public void setGcpProjectId(String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }

    public String getAzureSubscriptionId() {
        return azureSubscriptionId;
    } // ✅ Getter

    public void setAzureSubscriptionId(String azureSubscriptionId) {
        this.azureSubscriptionId = azureSubscriptionId;
    } // ✅ Setter

    public BigDecimal getPreDiscountTotal() {
        return preDiscountTotal;
    }

    public void setPreDiscountTotal(BigDecimal preDiscountTotal) {
        this.preDiscountTotal = preDiscountTotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getMarketplaceCharge() {
        return marketplaceCharge;
    }

    public void setMarketplaceCharge(BigDecimal marketplaceCharge) {
        this.marketplaceCharge = marketplaceCharge;
    }

    public BigDecimal getMarketplaceHoldingCostPercent() {
        return marketplaceHoldingCostPercent;
    }

    public void setMarketplaceHoldingCostPercent(BigDecimal marketplaceHoldingCostPercent) {
        this.marketplaceHoldingCostPercent = marketplaceHoldingCostPercent;
    }

    public BigDecimal getPrivatePricedTotal() {
        return privatePricedTotal;
    }

    public void setPrivatePricedTotal(BigDecimal privatePricedTotal) {
        this.privatePricedTotal = privatePricedTotal;
    }

    public BigDecimal getDiscountShieldedTotal() {
        return discountShieldedTotal;
    }

    public void setDiscountShieldedTotal(BigDecimal discountShieldedTotal) {
        this.discountShieldedTotal = discountShieldedTotal;
    }

    public List<PrivatePricingDto> getPrivatePricing() {
        return privatePricing;
    }

    public void setPrivatePricing(List<PrivatePricingDto> privatePricing) {
        this.privatePricing = privatePricing;
    }

    public boolean isIncludeIrn() {
        return includeIrn;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public String getUploadedFileName() {
        return uploadedFileName;
    }

    public void setUploadedFileName(String uploadedFileName) {
        this.uploadedFileName = uploadedFileName;
    }

    public void setProvisional(boolean provisional) {
        this.provisional = provisional;
    }

    public Double getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(Double exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public void setIncludeIrn(boolean includeIrn) {
        this.includeIrn = includeIrn;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public List<LineItemDto> getLineItems() {
        return lineItems;
    }

    public void setLineItems(List<LineItemDto> lineItems) {
        this.lineItems = lineItems;
    }

    public List<DiscountDto> getDiscounts() {
        return discounts;
    }

    public void setDiscounts(List<DiscountDto> discounts) {
        this.discounts = discounts;
    }

    // Inner class for Line Items
    public static class LineItemDto {
        private Long id;
        private String serviceName;
        private String regionName;
        private String resourceName;
        private String usageQuantity;
        private String unit;
        /** Usage type a rate line sits under; null except on invoices built from the report. */
        private String usageType;
        private BigDecimal cost;
        private boolean hidden;
        private BigDecimal quantity;
        private BigDecimal unitRate;

        /**
         * The rate an admin set for this line, when manual private pricing produced
         * {@link #cost}. Null on every line billed at provider cost.
         *
         * <p>Not to be confused with {@link #unitRate}, which is derived the other way
         * round -- cost divided by quantity -- and is set on every line.
         */
        private BigDecimal privateUnitRate;

        /**
         * The unit {@link #privateUnitRate} was quoted in, so the detail bill can print the
         * rate it actually billed at rather than inferring one from {@link #unit}.
         */
        private String privateRateUnit;

        /** What this line cost at provider rates, kept only once a private rate replaced it. */
        private BigDecimal originalCost;

        /**
         * Whether an invoice-wide or consumption-wide discount must skip this line.
         *
         * <p>Decided once, in {@link InvoiceDto#fromEntity}, from the applied rate's own
         * shield setting. The invoice PDF and the detail page both read it from here, so
         * neither re-derives a rule the saved totals were already computed from.
         */
        private boolean discountShielded;

        public static LineItemDto fromEntity(InvoiceLineItem item) {
            LineItemDto dto = new LineItemDto();
            dto.setId(item.getId());
            dto.setServiceName(item.getServiceName());
            dto.setRegionName(item.getRegionName());
            dto.setResourceName(item.getResourceName());
            dto.setUsageQuantity(item.getUsageQuantity());
            dto.setUnit(item.getUnit());
            dto.setUsageType(item.getUsageType());
            dto.setCost(item.getCost());
            dto.setHidden(item.isHidden());
            dto.setPrivateUnitRate(item.getPrivateUnitRate());
            dto.setPrivateRateUnit(item.getPrivateRateUnit());
            dto.setOriginalCost(item.getOriginalCost());

            try {
                String[] parts = item.getUsageQuantity().split(" ");
                dto.setQuantity(new BigDecimal(parts[0].replace(",", "")));
                if (dto.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    dto.setUnitRate(item.getCost().divide(dto.getQuantity(), 10, RoundingMode.HALF_UP));
                } else {
                    dto.setUnitRate(BigDecimal.ZERO);
                }
            } catch (Exception e) {
                dto.setQuantity(BigDecimal.ONE);
                dto.setUnitRate(item.getCost());
            }
            return dto;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getRegionName() {
            return regionName;
        }

        public void setRegionName(String regionName) {
            this.regionName = regionName;
        }

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public String getUsageQuantity() {
            return usageQuantity;
        }

        public void setUsageQuantity(String usageQuantity) {
            this.usageQuantity = usageQuantity;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public String getUsageType() {
            return usageType;
        }

        public void setUsageType(String usageType) {
            this.usageType = usageType;
        }

        public BigDecimal getCost() {
            return cost;
        }

        public void setCost(BigDecimal cost) {
            this.cost = cost;
        }

        public boolean isHidden() {
            return hidden;
        }

        public void setHidden(boolean hidden) {
            this.hidden = hidden;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public void setQuantity(BigDecimal quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getUnitRate() {
            return unitRate;
        }

        public void setUnitRate(BigDecimal unitRate) {
            this.unitRate = unitRate;
        }

        public BigDecimal getPrivateUnitRate() {
            return privateUnitRate;
        }

        public void setPrivateUnitRate(BigDecimal privateUnitRate) {
            this.privateUnitRate = privateUnitRate;
        }

        public String getPrivateRateUnit() {
            return privateRateUnit;
        }

        public void setPrivateRateUnit(String privateRateUnit) {
            this.privateRateUnit = privateRateUnit;
        }

        public BigDecimal getOriginalCost() {
            return originalCost;
        }

        public void setOriginalCost(BigDecimal originalCost) {
            this.originalCost = originalCost;
        }

        /**
         * Whether this line's amount came from a rate an admin set.
         *
         * <p>Not serialized. The detail bill is rendered from this DTO after a round trip
         * through JSON — the page re-reads the invoice and posts it back — and a property
         * with a getter but no setter cannot survive that: it goes out and comes back
         * unrecognised. Boot happens to ignore unknown properties, so it fails quietly
         * rather than loudly, which is worse. Callers that need this derive it from
         * {@link #privateUnitRate}, which does round-trip.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isPrivatelyPriced() {
            return privateUnitRate != null;
        }

        public boolean isDiscountShielded() {
            return discountShielded;
        }

        public void setDiscountShielded(boolean discountShielded) {
            this.discountShielded = discountShielded;
        }
    }

    /**
     * A rate an admin applied by hand to part of this invoice.
     *
     * <p>Carries both the provider's figure and the billed one for the scope, so the page
     * can show what the rate changed rather than only the rate itself.
     */
    public static class PrivatePricingDto {
        private Long id;
        private String serviceName;
        private String regionName;
        private String usageType;
        /** Set when the rate prices one charge line on its own. */
        private Long lineItemId;
        private String scopeLabel;
        private BigDecimal unitRate;
        private String rateUnit;
        private boolean excludeFromOverallDiscount;
        private Integer lineCount;
        private Integer skippedLineCount;
        /** What the repriced lines cost at provider rates. */
        private BigDecimal originalAmount;
        /** What those lines bill at this rate. */
        private BigDecimal pricedAmount;
        private String description;

        public static PrivatePricingDto fromEntity(InvoicePrivatePricing pricing) {
            PrivatePricingDto dto = new PrivatePricingDto();
            dto.setId(pricing.getId());
            dto.setServiceName(pricing.getServiceName());
            dto.setRegionName(pricing.getRegionName());
            dto.setUsageType(pricing.getUsageType());
            dto.setLineItemId(pricing.getLineItemId());
            dto.setScopeLabel(pricing.scopeLabel());
            dto.setUnitRate(pricing.getUnitRate());
            dto.setRateUnit(pricing.getRateUnit());
            dto.setExcludeFromOverallDiscount(pricing.isExcludeFromOverallDiscount());
            dto.setLineCount(pricing.getLineCount());
            dto.setSkippedLineCount(pricing.getSkippedLineCount());
            dto.setOriginalAmount(pricing.getOriginalAmount());
            dto.setPricedAmount(pricing.getPricedAmount());
            dto.setDescription(pricing.getDescription());
            return dto;
        }

        /**
         * Billed less provider cost: positive is margin, negative is a concession.
         *
         * <p>Not serialized, for the same reason as {@code LineItemDto.privatelyPriced}:
         * it has no setter, so it cannot come back through the detail bill's round trip.
         * The page computes it from {@code pricedAmount} and {@code originalAmount}.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public BigDecimal getMargin() {
            if (pricedAmount == null || originalAmount == null) {
                return null;
            }
            return pricedAmount.subtract(originalAmount);
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getRegionName() { return regionName; }
        public void setRegionName(String regionName) { this.regionName = regionName; }
        public String getUsageType() { return usageType; }
        public void setUsageType(String usageType) { this.usageType = usageType; }
        public Long getLineItemId() { return lineItemId; }
        public void setLineItemId(Long lineItemId) { this.lineItemId = lineItemId; }
        public String getScopeLabel() { return scopeLabel; }
        public void setScopeLabel(String scopeLabel) { this.scopeLabel = scopeLabel; }
        public BigDecimal getUnitRate() { return unitRate; }
        public void setUnitRate(BigDecimal unitRate) { this.unitRate = unitRate; }
        public String getRateUnit() { return rateUnit; }
        public void setRateUnit(String rateUnit) { this.rateUnit = rateUnit; }
        public boolean isExcludeFromOverallDiscount() { return excludeFromOverallDiscount; }
        public void setExcludeFromOverallDiscount(boolean excludeFromOverallDiscount) {
            this.excludeFromOverallDiscount = excludeFromOverallDiscount;
        }
        public Integer getLineCount() { return lineCount; }
        public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
        public Integer getSkippedLineCount() { return skippedLineCount; }
        public void setSkippedLineCount(Integer skippedLineCount) { this.skippedLineCount = skippedLineCount; }
        public BigDecimal getOriginalAmount() { return originalAmount; }
        public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
        public BigDecimal getPricedAmount() { return pricedAmount; }
        public void setPricedAmount(BigDecimal pricedAmount) { this.pricedAmount = pricedAmount; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // Inner class for Discounts
    public static class DiscountDto {
        private Long id;
        private String serviceName;
        private BigDecimal percentage;
        private String description;

        public static DiscountDto fromEntity(Discount discount) {
            DiscountDto dto = new DiscountDto();
            dto.setId(discount.getId());
            dto.setServiceName(discount.getServiceName());
            dto.setPercentage(discount.getPercentage());
            dto.setDescription(discount.getDescription());
            return dto;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public BigDecimal getPercentage() {
            return percentage;
        }

        public void setPercentage(BigDecimal percentage) {
            this.percentage = percentage;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}