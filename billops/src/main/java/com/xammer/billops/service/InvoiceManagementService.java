package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.xammer.cloud.domain.*; 
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.GcpBillingDashboardDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.AppUserRepository; 
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.controller.AdminCloudFrontController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut; 
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xammer.billops.dto.InvoiceUpdateDto;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository; 
import com.xammer.billops.domain.CloudFrontPrivateRate; 
import com.xammer.billops.service.CloudFrontUsageService.CloudFrontUsageDto; 
import java.math.MathContext; 

import com.xammer.billops.service.CloudFrontUsageService;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*; 
import java.util.stream.Collectors;

@Service
public class InvoiceManagementService {

    private final InvoiceRepository invoiceRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final DiscountRepository discountRepository;
    private final BillingService billingService;
    private final GcpCostService gcpCostService;
    private final AppUserRepository appUserRepository; 
    private final EmailService emailService; 
    private static final Logger logger = LoggerFactory.getLogger(InvoiceManagementService.class);
    private final CloudFrontUsageService cloudFrontUsageService;
    private final CloudFrontPrivateRateRepository privateRateRepository;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache; 

    // Define Manual Cache Key
    private static final String INVOICE_LIST_CACHE_KEY = "billops:invoices:list:admin";
    private static final long CACHE_TTL_MINUTES = 60;

    public InvoiceManagementService(InvoiceRepository invoiceRepository,
                                  CloudAccountRepository cloudAccountRepository,
                                  DiscountRepository discountRepository,
                                  BillingService billingService,
                                  GcpCostService gcpCostService,
                                  AppUserRepository appUserRepository,
                                  EmailService emailService,
                                  CloudFrontUsageService cloudFrontUsageService,
                                  CloudFrontPrivateRateRepository privateRateRepository,
                                  ClientRepository clientRepository,
                                  RedisCacheService redisCache) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.discountRepository = discountRepository;
        this.billingService = billingService;
        this.gcpCostService = gcpCostService;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
        this.cloudFrontUsageService = cloudFrontUsageService;
        this.privateRateRepository = privateRateRepository;
        this.clientRepository = clientRepository;
        this.redisCache = redisCache;
    }

    /**
     * Helper to invalidate the manual Redis cache for the invoice list.
     */
    private void evictInvoiceListCache() {
        redisCache.evict(INVOICE_LIST_CACHE_KEY);
        logger.info("Evicted invoice list cache: {}", INVOICE_LIST_CACHE_KEY);
    }

    public InvoiceDto applyDiscountToTemporaryInvoice(InvoiceDto invoiceDto, DiscountRequestDto discountRequest) {
        InvoiceDto.DiscountDto newDiscount = new InvoiceDto.DiscountDto();
        newDiscount.setServiceName(discountRequest.getServiceName());
        newDiscount.setPercentage(discountRequest.getPercentage());
        newDiscount.setDescription(String.format("%.2f%% discount for %s",
                discountRequest.getPercentage(),
                "ALL".equalsIgnoreCase(discountRequest.getServiceName()) ? "Overall Bill" : discountRequest.getServiceName()
        ));

        if (invoiceDto.getDiscounts() == null) {
            invoiceDto.setDiscounts(new ArrayList<>());
        }
        invoiceDto.getDiscounts().add(newDiscount);

        recalculateTotalsForDto(invoiceDto);

        return invoiceDto;
    }

    private void recalculateTotalsForDto(InvoiceDto invoiceDto) {
        BigDecimal totalDiscount = BigDecimal.ZERO;

        BigDecimal preDiscountTotal = Optional.ofNullable(invoiceDto.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        List<InvoiceDto.DiscountDto> discounts = Optional.ofNullable(invoiceDto.getDiscounts()).orElse(Collections.emptyList());
        List<InvoiceDto.LineItemDto> lineItems = Optional.ofNullable(invoiceDto.getLineItems()).orElse(Collections.emptyList());

        for (InvoiceDto.DiscountDto disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                                            .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); 

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(preDiscountTotal.multiply(percentage));
            } else {
                 String discountServiceName = Optional.ofNullable(disc.getServiceName()).orElse("");
                BigDecimal serviceTotal = lineItems.stream()
                        .filter(item -> discountServiceName.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }

        invoiceDto.setDiscountAmount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        invoiceDto.setAmount(preDiscountTotal.subtract(invoiceDto.getDiscountAmount()));
    }

    @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Cloud account not found for AWS Account ID: " + accountId); 
        }
        CloudAccount cloudAccount = accounts.get(0);

        // FIX: Use correct method signature and remove .orElse()
        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReport(
                Collections.singletonList(accountId), year, month, false);

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
         try {
             invoice.setClient(cloudAccount.getClient());
         } catch (Exception e) {
             logger.error("Could not get Client for CloudAccount ID {} during temporary invoice generation: {}", cloudAccount.getId(), e.getMessage());
             throw new RuntimeException("Failed to associate client with temporary invoice", e);
         }

        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("TEMP-" + UUID.randomUUID().toString().toUpperCase().substring(0, 8));

        List<InvoiceLineItem> lineItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ServiceCostDetailDto serviceDto : detailedReport) {
             List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts()).orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                 List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources()).orElse(Collections.emptyList());
                for (var resourceDto : resources) {
                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(resourceDto.getResourceName());
                    lineItem.setUsageQuantity(String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0))); 
                    lineItem.setUnit(resourceDto.getUnit());
                    BigDecimal resourceCost = BigDecimal.valueOf(Optional.ofNullable(resourceDto.getCost()).orElse(0.0)); 
                    lineItem.setCost(resourceCost.setScale(4, RoundingMode.HALF_UP)); 
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }

        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost.setScale(4, RoundingMode.HALF_UP));
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        invoice.setAmount(totalCost.setScale(4, RoundingMode.HALF_UP));

        return invoice; 
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true) 
    public Invoice generateDraftInvoice(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst();
        if (accountOpt.isEmpty()) {
            accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        }

        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found for identifier: " + accountId));

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();

        if ("GCP".equals(cloudAccount.getProvider())) {
            try {
                GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDtoAndCache(accountId)
                        .orElse(null); 

                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (GcpBillingDashboardDto.ServiceBreakdown service : dashboardDto.getServiceBreakdown()) {
                        BigDecimal serviceAmount = Optional.ofNullable(service.getAmount()).orElse(BigDecimal.ZERO);
                        double totalServiceCost = serviceAmount.doubleValue();

                        if (totalServiceCost <= 0) continue;

                        ResourceCostDto serviceAsResource = new ResourceCostDto(
                                service.getServiceName(), "Service-level total", totalServiceCost, 1.0, "N/A");
                        RegionCostDto globalRegion = new RegionCostDto("global", totalServiceCost, List.of(serviceAsResource));
                        ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto(service.getServiceName(), totalServiceCost, List.of(globalRegion));
                        detailedReport.add(serviceDetail);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to generate GCP invoice data for account {}", accountId, e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to generate GCP invoice: " + e.getMessage(), e);
            }
        } else { // AWS
            // FIX: Use correct method signature and remove .orElse()
            detailedReport = billingService.getDetailedBillingReport(
                    Collections.singletonList(accountId), year, month, false);
        }

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(cloudAccount.getClient()); 
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber(UUID.randomUUID().toString().toUpperCase().substring(0, 12));

        List<InvoiceLineItem> lineItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ServiceCostDetailDto serviceDto : detailedReport) {
            List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts()).orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                 List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources()).orElse(Collections.emptyList());
                for (var resourceDto : resources) {
                    double costValue = Optional.ofNullable(resourceDto.getCost()).orElse(0.0);
                    if (costValue <= 0) continue;

                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(resourceDto.getResourceName());
                    lineItem.setUsageQuantity(String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0))); 
                    lineItem.setUnit(resourceDto.getUnit());
                    BigDecimal resourceCost = BigDecimal.valueOf(costValue);
                    lineItem.setCost(resourceCost.setScale(4, RoundingMode.HALF_UP)); 
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }

        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost.setScale(4, RoundingMode.HALF_UP)); 
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        invoice.setAmount(totalCost.setScale(4, RoundingMode.HALF_UP));

        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice applyDiscountToInvoice(Long invoiceId, String serviceName, BigDecimal percentage) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot apply discount to a finalized invoice.");
        }

         if (percentage == null || percentage.compareTo(BigDecimal.ZERO) < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Discount percentage must be between 0 and 100.");
         }

        Discount discount = new Discount();
        discount.setInvoice(invoice);
         try {
             discount.setClient(invoice.getClient());
         } catch (Exception e) {
              logger.error("Could not get Client for Invoice ID {} during discount application: {}", invoice.getId(), e.getMessage());
              throw new RuntimeException("Failed to associate client with discount", e);
         }

        discount.setServiceName(serviceName);
        discount.setPercentage(percentage.setScale(2, RoundingMode.HALF_UP)); 
        discount.setDescription(String.format("%.2f%% discount for %s",
                percentage, "ALL".equalsIgnoreCase(serviceName) ? "Overall Bill" : serviceName));

        discount = discountRepository.save(discount);

        if (invoice.getDiscounts() == null) {
            invoice.setDiscounts(new ArrayList<>());
        }
        invoice.getDiscounts().add(discount);

        recalculateTotals(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice removeDiscountFromInvoice(Long invoiceId, Long discountId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot modify a finalized invoice.");
        }

        Optional<Discount> discountToRemoveOpt = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList()) 
                                                        .stream()
                                                        .filter(d -> d.getId() != null && d.getId().equals(discountId))
                                                        .findFirst();

        if (discountToRemoveOpt.isPresent()) {
            Discount discountToRemove = discountToRemoveOpt.get();
            invoice.getDiscounts().remove(discountToRemove);
            discountRepository.delete(discountToRemove);
            recalculateTotals(invoice);
            Invoice saved = invoiceRepository.save(invoice);
            evictInvoiceListCache(); // Manual Eviction
            return saved;
        } else {
            logger.warn("Discount with ID {} not found on invoice {}", discountId, invoiceId);
             return invoice; 
        }
    }

    private void recalculateTotals(Invoice invoice) {
        BigDecimal preDiscountTotal = Optional.ofNullable(invoice.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        List<Discount> discounts = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList());
        List<InvoiceLineItem> lineItems = Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList());

        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (Discount disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                                            .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); 

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(preDiscountTotal.multiply(percentage));
            } else {
                 String discountServiceName = Optional.ofNullable(disc.getServiceName()).orElse("");
                BigDecimal serviceTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> discountServiceName.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }
        invoice.setDiscountAmount(totalDiscount.setScale(4, RoundingMode.HALF_UP));
        BigDecimal visiblePreDiscountTotal = lineItems.stream()
                                                    .filter(item -> !item.isHidden())
                                                    .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        invoice.setPreDiscountTotal(visiblePreDiscountTotal.setScale(4, RoundingMode.HALF_UP)); 
        invoice.setAmount(invoice.getPreDiscountTotal().subtract(invoice.getDiscountAmount()).setScale(4, RoundingMode.HALF_UP));
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice finalizeInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

         recalculateTotals(invoice);

        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        
        Invoice finalizedInvoice = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction

        // Email Notification
        try {
            Client client = finalizedInvoice.getClient();
            if (client == null) {
                 logger.warn("Cannot send invoice email: Invoice ID {} has no associated client.", finalizedInvoice.getId());
                 return finalizedInvoice; 
            }
    
            List<AppUser> users = appUserRepository.findByClientId(client.getId());
            if (users.isEmpty()) {
                 logger.warn("No users found for client ID {}. Cannot send invoice email.", client.getId());
                 return finalizedInvoice; 
            }
    
            String subject = String.format("Your XamOps Invoice (%s) is Ready", finalizedInvoice.getInvoiceNumber());
            String text = String.format(
                "Hello,\n\nYour invoice (%s) for the billing period %s is now finalized and available for review.\n\n" +
                "Total Amount: $%.2f\n\n" +
                "You can view your invoice by logging into the XamOps portal.\n\n" +
                "Thank you,\nThe XamOps Team",
                finalizedInvoice.getInvoiceNumber(),
                finalizedInvoice.getBillingPeriod(),
                finalizedInvoice.getAmount() 
            );
    
            for (AppUser user : users) {
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    logger.info("Sending finalized invoice email for invoice {} to user {} ({})", finalizedInvoice.getId(), user.getUsername(), user.getEmail());
                    emailService.sendSimpleMessage(user.getEmail(), subject, text);
                } else {
                     logger.warn("User {} (ID: {}) has no email address. Skipping invoice email.", user.getUsername(), user.getId());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send invoice finalization email for Invoice ID {}. The invoice was still finalized.", finalizedInvoice.getId(), e);
        }

        return finalizedInvoice; 
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "{#accountId, #year, #month}")
    public InvoiceDto getInvoiceForUser(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst();
        if (accountOpt.isEmpty()) {
            accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        }

        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found for identifier: " + accountId));

        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        List<Invoice> invoices = invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(), billingPeriod, Invoice.InvoiceStatus.FINALIZED);

        if (invoices.isEmpty()) return null; 

        Invoice singleInvoice = (invoices.size() > 1) ?
            invoices.stream().max(Comparator.comparing(Invoice::getId)).orElse(invoices.get(0)) :
            invoices.get(0);

        return InvoiceDto.fromEntity(singleInvoice);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "#invoiceId") 
    public InvoiceDto getInvoiceForAdmin(Long invoiceId) { 
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found with ID: " + invoiceId));
        return InvoiceDto.fromEntity(invoice);
    }

    public ByteArrayInputStream generatePdfForInvoice(Long invoiceId) {
        InvoiceDto invoiceDto = getInvoiceForAdmin(invoiceId); 
        return generatePdfFromDto(invoiceDto); 
    }

    public ByteArrayInputStream generatePdfFromDto(InvoiceDto dto) {
        Invoice invoice = new Invoice(); 
        invoice.setInvoiceNumber(dto.getInvoiceNumber());
        invoice.setBillingPeriod(dto.getBillingPeriod());
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setPreDiscountTotal(dto.getPreDiscountTotal());
        invoice.setDiscountAmount(dto.getDiscountAmount());
        invoice.setAmount(dto.getAmount());

        CloudAccount tempAccount = new CloudAccount();
        tempAccount.setAccountName(dto.getAccountName());
        tempAccount.setAwsAccountId(dto.getAwsAccountId()); 
        invoice.setCloudAccount(tempAccount);

        if (dto.getLineItems() != null) {
            List<InvoiceLineItem> lineItems = dto.getLineItems().stream().map(itemDto -> {
                InvoiceLineItem item = new InvoiceLineItem();
                item.setServiceName(itemDto.getServiceName());
                item.setResourceName(itemDto.getResourceName());
                item.setRegionName(itemDto.getRegionName());
                item.setUsageQuantity(itemDto.getUsageQuantity());
                item.setUnit(itemDto.getUnit());
                item.setCost(itemDto.getCost());
                item.setHidden(itemDto.isHidden()); 
                item.setInvoice(invoice); 
                return item;
            }).collect(Collectors.toList());
            invoice.setLineItems(lineItems);
        } else {
            invoice.setLineItems(new ArrayList<>());
        }

        if (dto.getDiscounts() != null) {
            List<Discount> discounts = dto.getDiscounts().stream().map(discountDto -> {
                Discount discount = new Discount();
                discount.setId(discountDto.getId()); 
                discount.setServiceName(discountDto.getServiceName());
                discount.setPercentage(discountDto.getPercentage());
                discount.setDescription(discountDto.getDescription());
                discount.setInvoice(invoice); 
                return discount;
            }).collect(Collectors.toList());
            invoice.setDiscounts(discounts);
        } else {
             invoice.setDiscounts(new ArrayList<>());
        }

        return createPdfStream(invoice);
    }

    private ByteArrayInputStream createPdfStream(Invoice invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            document.add(new Paragraph("Xammer Invoice").setFontColor(ColorConstants.BLUE).setFontSize(24).setBold());
            document.add(new Paragraph("Official Bill Summary").setFontSize(16).setBold());
            document.add(new Paragraph("\n"));

            Table headerTable = new Table(UnitValue.createPercentArray(new float[]{1, 1}));
            headerTable.setWidth(UnitValue.createPercentValue(100));
            
            CloudAccount account = invoice.getCloudAccount(); 
            String accountName = "N/A";
            String accountIdDisplay = "N/A"; 

             if (account != null) {
                 accountName = Optional.ofNullable(account.getAccountName()).orElse("N/A");
                 if (account.getAwsAccountId() != null) {
                     accountIdDisplay = account.getAwsAccountId();
                 } else if (account.getGcpProjectId() != null) {
                     accountIdDisplay = account.getGcpProjectId();
                 }
             }

            headerTable.addCell(new Cell().add(new Paragraph("Account Name:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(accountName)).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Account ID:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(accountIdDisplay)).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Billing Period:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(invoice.getBillingPeriod()).orElse("N/A"))).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Invoice Date:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(invoice.getInvoiceDate()).map(LocalDate::toString).orElse("N/A"))).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Invoice Number:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(invoice.getInvoiceNumber()).orElse("N/A"))).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Status:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(invoice.getStatus()).map(Enum::name).orElse("N/A"))).setBorder(null));

            document.add(headerTable);
            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Charges by service").setFontSize(14).setBold());

            Table chargesTable = new Table(UnitValue.createPercentArray(new float[]{4, 3, 2, 2}));
            chargesTable.setWidth(UnitValue.createPercentValue(100));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Service")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Resource")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Usage")).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Amount (USD)")).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.RIGHT));

            List<InvoiceLineItem> lineItems = Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList());

            Map<String, List<InvoiceLineItem>> groupedByService = lineItems.stream()
                    .filter(item -> !item.isHidden())
                    .collect(Collectors.groupingBy(
                         item -> Optional.ofNullable(item.getServiceName()).orElse("Uncategorized")
                    ));

            NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
            List<String> sortedServiceNames = groupedByService.keySet().stream().sorted().collect(Collectors.toList());

             for (String serviceName : sortedServiceNames) {
                 List<InvoiceLineItem> itemsForService = groupedByService.get(serviceName);
                chargesTable.addCell(new Cell(1, 4).add(new Paragraph(serviceName).setBold()));

                 itemsForService.sort(Comparator.comparing(
                     (InvoiceLineItem item) -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO)
                 ).reversed());

                for (InvoiceLineItem item : itemsForService) {
                    chargesTable.addCell(new Cell().add(new Paragraph("").setMarginLeft(15)).setBorder(null)); 
                    chargesTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(item.getResourceName()).orElse("N/A"))));
                    chargesTable.addCell(new Cell().add(new Paragraph(
                            Optional.ofNullable(item.getUsageQuantity()).orElse("0") + " " + Optional.ofNullable(item.getUnit()).orElse("")
                    )).setTextAlignment(TextAlignment.CENTER));
                    chargesTable.addCell(new Cell().add(new Paragraph(
                            currencyFormatter.format(Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                    )).setTextAlignment(TextAlignment.RIGHT));
                }

                BigDecimal serviceTotal = itemsForService.stream()
                                     .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                                     .reduce(BigDecimal.ZERO, BigDecimal::add);

                chargesTable.addCell(new Cell(1, 3).setBorder(null)); 
                chargesTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(serviceTotal))).setBold().setTextAlignment(TextAlignment.RIGHT));
            }

            document.add(chargesTable);
            document.add(new Paragraph("\n"));

            Table totalsTable = new Table(UnitValue.createPercentArray(new float[]{3, 1}));
            totalsTable.setWidth(UnitValue.createPercentValue(40)).setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.RIGHT);

            BigDecimal preDiscountTotal = Optional.ofNullable(invoice.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
            BigDecimal discountAmount = Optional.ofNullable(invoice.getDiscountAmount()).orElse(BigDecimal.ZERO);
            BigDecimal amount = Optional.ofNullable(invoice.getAmount()).orElse(BigDecimal.ZERO);

            totalsTable.addCell(new Cell().add(new Paragraph("Subtotal:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(preDiscountTotal))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));

            if (discountAmount.compareTo(BigDecimal.ZERO) != 0) {
                totalsTable.addCell(new Cell().add(new Paragraph("Discounts:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
                totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(discountAmount.negate()))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            }

            totalsTable.addCell(new Cell().add(new Paragraph("Total:").setBold()).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(amount))).setBold().setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            document.add(totalsTable);

        } catch (Exception e) {
             logger.error("Error creating PDF for invoice {}: {}", (invoice != null ? invoice.getId() : "UNKNOWN"), e.getMessage(), e);
             throw new RuntimeException("Failed to generate PDF: " + e.getMessage(), e);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice updateInvoice(Long invoiceId, InvoiceUpdateDto invoiceUpdateDto) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot edit a finalized invoice.");
        }

         if (invoice.getLineItems() == null) {
             invoice.setLineItems(new ArrayList<>());
         } else {
            invoice.getLineItems().clear(); 
         }

        BigDecimal newPreDiscountTotal = BigDecimal.ZERO;
        List<InvoiceUpdateDto.LineItemUpdateDto> lineItemDtos = Optional.ofNullable(invoiceUpdateDto.getLineItems())
                                                                    .orElse(Collections.emptyList());

        for (InvoiceUpdateDto.LineItemUpdateDto itemDto : lineItemDtos) {
            InvoiceLineItem newLineItem = new InvoiceLineItem();
            newLineItem.setInvoice(invoice); 
            newLineItem.setServiceName(itemDto.getServiceName());
            newLineItem.setRegionName(itemDto.getRegionName());
            newLineItem.setResourceName(itemDto.getResourceName());
            newLineItem.setUsageQuantity(itemDto.getUsageQuantity());
            newLineItem.setUnit(itemDto.getUnit());
            BigDecimal itemCost = Optional.ofNullable(itemDto.getCost()).orElse(BigDecimal.ZERO);
            newLineItem.setCost(itemCost.setScale(4, RoundingMode.HALF_UP)); 

            newLineItem.setHidden(itemDto.isHidden());

            invoice.getLineItems().add(newLineItem); 
            if (!itemDto.isHidden()) {
                newPreDiscountTotal = newPreDiscountTotal.add(itemCost);
            }
        }

        invoice.setPreDiscountTotal(newPreDiscountTotal.setScale(4, RoundingMode.HALF_UP)); 
        recalculateTotals(invoice); 

        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction
        return saved;
    }

    // --- MANUAL CACHING METHODS FOR ADMIN INVOICE LIST ---

    @Transactional(readOnly = true)
    public Optional<List<InvoiceDto>> getCachedAllInvoices() {
        return redisCache.get(INVOICE_LIST_CACHE_KEY, new TypeReference<List<InvoiceDto>>() {});
    }

    @Transactional(readOnly = true)
    public List<InvoiceDto> getAllInvoicesAndCache() {
        logger.debug("Fetching FRESH 'allAdminInvoices' and updating cache");
        List<Invoice> invoices = invoiceRepository.findAll();
        // Convert to DTO inside the transactional method to load lazy collections
        List<InvoiceDto> dtos = invoices.stream().map(InvoiceDto::fromEntity).collect(Collectors.toList());
        redisCache.put(INVOICE_LIST_CACHE_KEY, dtos, CACHE_TTL_MINUTES);
        return dtos;
    }

    // --- END: MANUAL CACHING METHODS ---

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice generateCloudFrontInvoice(String accountId, int year, int month, List<CloudFrontUsageDto> usageData) {
        
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + accountId));

        Client client = cloudAccount.getClient();
        if (client == null) {
            throw new RuntimeException("Account " + accountId + " is not associated with a client.");
        }

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(client);
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("CF-" + UUID.randomUUID().toString().toUpperCase().substring(0, 10));
        invoice.setLineItems(new ArrayList<>());
        
        BigDecimal totalAwsCost = BigDecimal.ZERO; 

        for (CloudFrontUsageDto usage : usageData) {
            BigDecimal originalCost = BigDecimal.valueOf(usage.getCost());

            InvoiceLineItem lineItem = new InvoiceLineItem();
            lineItem.setInvoice(invoice);
            lineItem.setServiceName("Amazon CloudFront");
            lineItem.setRegionName(usage.getRegion());
            lineItem.setResourceName(usage.getUsageType());
            lineItem.setUsageQuantity(String.format("%.3f", usage.getQuantity()));
            lineItem.setUnit(usage.getUnit()); 
            lineItem.setCost(originalCost.setScale(4, RoundingMode.HALF_UP)); 
            
            invoice.getLineItems().add(lineItem);
            totalAwsCost = totalAwsCost.add(originalCost);
        }

        invoice.setPreDiscountTotal(totalAwsCost.setScale(4, RoundingMode.HALF_UP));
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)); 
        invoice.setAmount(totalAwsCost.setScale(4, RoundingMode.HALF_UP));

        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice updateInvoiceLineItems(Long invoiceId, List<com.xammer.billops.controller.AdminCloudFrontController.LineItemUpdate> updates) {
        logger.info("Updating CloudFront invoice {} with {} line item changes", invoiceId, updates.size());
        
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new RuntimeException("Cannot edit finalized invoice");
        }
        
        BigDecimal newTotal = BigDecimal.ZERO;
        
        for (com.xammer.billops.controller.AdminCloudFrontController.LineItemUpdate update : updates) {
            InvoiceLineItem item = invoice.getLineItems().stream()
                .filter(li -> li.getId().equals(update.id))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Line item not found: " + update.id));
            
            String[] parts = item.getUsageQuantity().split(" ");
            BigDecimal quantity;
            try {
                quantity = new BigDecimal(parts[0]);
            } catch (Exception e) {
                quantity = BigDecimal.ONE;
            }
            
            BigDecimal cost = update.unitRate
                .multiply(quantity)
                .setScale(4, RoundingMode.HALF_UP);
            
            item.setCost(cost);
            
            if (!item.isHidden()) {
                newTotal = newTotal.add(cost);
            }
        }
        
        invoice.setPreDiscountTotal(newTotal);
        invoice.setAmount(newTotal);
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        
        Invoice savedInvoice = invoiceRepository.save(invoice);
        evictInvoiceListCache(); // Manual Eviction
        logger.info("CloudFront invoice {} updated and finalized with new total: {}", invoiceId, newTotal);
        
        return savedInvoice;
    }
}