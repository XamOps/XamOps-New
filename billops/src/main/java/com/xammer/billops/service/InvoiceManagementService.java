package com.xammer.billops.service;

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
import com.xammer.billops.repository.AppUserRepository; // 1. ADD IMPORT
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.Discount;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xammer.billops.dto.InvoiceUpdateDto;
// It might be necessary to import Hibernate for initialization if needed,
// but let's try without it first as the DTO conversion *should* trigger loading.
// import org.hibernate.Hibernate;

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
    private final AppUserRepository appUserRepository; // 2. ADD REPO
    private final EmailService emailService; // 3. ADD SERVICE
    private static final Logger logger = LoggerFactory.getLogger(InvoiceManagementService.class);

    // 4. MODIFY CONSTRUCTOR
    public InvoiceManagementService(InvoiceRepository invoiceRepository,
                                  CloudAccountRepository cloudAccountRepository,
                                  DiscountRepository discountRepository,
                                  BillingService billingService,
                                  GcpCostService gcpCostService,
                                  AppUserRepository appUserRepository, // Add
                                  EmailService emailService) { // Add
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.discountRepository = discountRepository;
        this.billingService = billingService;
        this.gcpCostService = gcpCostService;
        this.appUserRepository = appUserRepository; // Add
        this.emailService = emailService; // Add
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

        // Ensure preDiscountTotal and discounts list are not null
        BigDecimal preDiscountTotal = Optional.ofNullable(invoiceDto.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        List<InvoiceDto.DiscountDto> discounts = Optional.ofNullable(invoiceDto.getDiscounts()).orElse(Collections.emptyList());
        List<InvoiceDto.LineItemDto> lineItems = Optional.ofNullable(invoiceDto.getLineItems()).orElse(Collections.emptyList());


        for (InvoiceDto.DiscountDto disc : discounts) {
             // Ensure percentage is not null before calculations
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                                            .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); // Add scale and rounding

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(preDiscountTotal.multiply(percentage));
            } else {
                 // Ensure serviceName is not null for comparison
                 String discountServiceName = Optional.ofNullable(disc.getServiceName()).orElse("");
                BigDecimal serviceTotal = lineItems.stream()
                        .filter(item -> discountServiceName.equalsIgnoreCase(item.getServiceName()))
                        // Ensure cost is not null
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }

        invoiceDto.setDiscountAmount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        // Ensure amount calculation handles null preDiscountTotal
        invoiceDto.setAmount(preDiscountTotal.subtract(invoiceDto.getDiscountAmount()));
    }

    @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Cloud account not found for AWS Account ID: " + accountId); // More specific error
        }
        CloudAccount cloudAccount = accounts.get(0);

        // --- FIX: Call the correct method and handle Optional ---
        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReportAndCache(
                Collections.singletonList(accountId), year, month)
                .orElse(Collections.emptyList()); // Get the list or an empty list if Optional is empty
        // --- END FIX ---

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        // Ensure client is loaded before accessing it - fetch the account again with eager loading or use a specific query if needed
        // For simplicity, let's assume getClient() works here, but this could cause LazyInitializationException later
         try {
             invoice.setClient(cloudAccount.getClient());
         } catch (Exception e) {
              logger.error("Could not get Client for CloudAccount ID {} during temporary invoice generation: {}", cloudAccount.getId(), e.getMessage());
              // Handle this case - maybe throw exception or assign a default/null client if allowed?
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
                    lineItem.setUsageQuantity(String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0))); // Handle null quantity
                    lineItem.setUnit(resourceDto.getUnit());
                    BigDecimal resourceCost = BigDecimal.valueOf(Optional.ofNullable(resourceDto.getCost()).orElse(0.0)); // Handle null cost
                    lineItem.setCost(resourceCost.setScale(4, RoundingMode.HALF_UP)); // Set scale
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }

        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost.setScale(4, RoundingMode.HALF_UP));
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        invoice.setAmount(totalCost.setScale(4, RoundingMode.HALF_UP));


        return invoice; // Note: This temporary invoice is NOT saved to the DB here.
    }


    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true) // Evict both caches just in case
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
                GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDto(accountId);

                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (GcpBillingDashboardDto.ServiceBreakdown service : dashboardDto.getServiceBreakdown()) {
                         // Ensure amount is not null
                        BigDecimal serviceAmount = Optional.ofNullable(service.getAmount()).orElse(BigDecimal.ZERO);
                        double totalServiceCost = serviceAmount.doubleValue();

                         // Skip if cost is zero or negative
                        if (totalServiceCost <= 0) continue;

                        ResourceCostDto serviceAsResource = new ResourceCostDto(
                                service.getServiceName(),
                                "Service-level total",
                                totalServiceCost,
                                1.0,
                                "N/A"
                        );

                        RegionCostDto globalRegion = new RegionCostDto(
                                "global",
                                totalServiceCost,
                                List.of(serviceAsResource)
                        );

                        ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto(
                                service.getServiceName(),
                                totalServiceCost,
                                List.of(globalRegion)
                        );
                        detailedReport.add(serviceDetail);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to generate GCP invoice data for account {}", accountId, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Failed to generate GCP invoice: " + e.getMessage(), e);
            }
        } else { // Assume AWS
             // --- FIX: Call the correct method and handle Optional ---
            detailedReport = billingService.getDetailedBillingReportAndCache(
                    Collections.singletonList(accountId), year, month)
                    .orElse(Collections.emptyList()); // Get the list or an empty list if Optional is empty
             // --- END FIX ---
        }

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(cloudAccount.getClient()); // Assumes client is loaded or fetch type allows access here
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber(UUID.randomUUID().toString().toUpperCase().substring(0, 12));

        List<InvoiceLineItem> lineItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ServiceCostDetailDto serviceDto : detailedReport) {
             // Check if regionCosts is null
            List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts()).orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                 // Check if resources is null
                 List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources()).orElse(Collections.emptyList());
                for (var resourceDto : resources) {
                     // Skip if resource cost is zero or negative or null
                    double costValue = Optional.ofNullable(resourceDto.getCost()).orElse(0.0);
                    if (costValue <= 0) continue;

                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(resourceDto.getResourceName());
                    lineItem.setUsageQuantity(String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0))); // Handle null quantity
                    lineItem.setUnit(resourceDto.getUnit());
                    // Ensure cost is not null before converting
                    BigDecimal resourceCost = BigDecimal.valueOf(costValue);
                    lineItem.setCost(resourceCost.setScale(4, RoundingMode.HALF_UP)); // Set scale
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }


        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost.setScale(4, RoundingMode.HALF_UP)); // Standard scale
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        invoice.setAmount(totalCost.setScale(4, RoundingMode.HALF_UP));


        return invoiceRepository.save(invoice);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice applyDiscountToInvoice(Long invoiceId, String serviceName, BigDecimal percentage) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));


        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot apply discount to a finalized invoice.");
        }

         // Validate percentage
        if (percentage == null || percentage.compareTo(BigDecimal.ZERO) < 0 || percentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Discount percentage must be between 0 and 100.");
        }


        Discount discount = new Discount();
        discount.setInvoice(invoice);
         // Eagerly fetch or ensure client is accessible
        try {
            discount.setClient(invoice.getClient());
         } catch (Exception e) {
             logger.error("Could not get Client for Invoice ID {} during discount application: {}", invoice.getId(), e.getMessage());
             throw new RuntimeException("Failed to associate client with discount", e);
         }

        discount.setServiceName(serviceName);
        discount.setPercentage(percentage.setScale(2, RoundingMode.HALF_UP)); // Standardize scale
        discount.setDescription(String.format("%.2f%% discount for %s",
                percentage,
                "ALL".equalsIgnoreCase(serviceName) ? "Overall Bill" : serviceName
        ));

        // Save the discount first to get an ID
        discount = discountRepository.save(discount);

        // Add the persisted discount to the invoice's list
        if (invoice.getDiscounts() == null) {
            invoice.setDiscounts(new ArrayList<>());
        }
        invoice.getDiscounts().add(discount);


        recalculateTotals(invoice);

        // Save the invoice *after* adding the discount and recalculating
        return invoiceRepository.save(invoice);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice removeDiscountFromInvoice(Long invoiceId, Long discountId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));


        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot modify a finalized invoice.");
        }

        Optional<Discount> discountToRemoveOpt = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList()) // Handle null list
                                                        .stream()
                                                        .filter(d -> d.getId() != null && d.getId().equals(discountId))
                                                        .findFirst();


        if (discountToRemoveOpt.isPresent()) {
            Discount discountToRemove = discountToRemoveOpt.get();
             // Remove from the collection
            invoice.getDiscounts().remove(discountToRemove);
             // Explicitly delete from the repository
            discountRepository.delete(discountToRemove);
            recalculateTotals(invoice);
            return invoiceRepository.save(invoice); // Save the invoice to persist the removal
        } else {
            logger.warn("Discount with ID {} not found on invoice {}", discountId, invoiceId);
            // Optionally throw an exception or just return the unchanged invoice
            // throw new RuntimeException("Discount not found on this invoice.");
             return invoice; // Return unchanged invoice if discount wasn't found
        }
    }


    private void recalculateTotals(Invoice invoice) {
         // Ensure necessary fields are not null
        BigDecimal preDiscountTotal = Optional.ofNullable(invoice.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        List<Discount> discounts = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList());
        List<InvoiceLineItem> lineItems = Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList());

        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (Discount disc : discounts) {
             // Ensure percentage is not null
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                                            .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP); // Use scale 4 for intermediate calcs


            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(preDiscountTotal.multiply(percentage));
            } else {
                 String discountServiceName = Optional.ofNullable(disc.getServiceName()).orElse("");
                BigDecimal serviceTotal = lineItems.stream()
                         // Filter out hidden items before calculating service total for discount
                        .filter(item -> !item.isHidden())
                        .filter(item -> discountServiceName.equalsIgnoreCase(item.getServiceName()))
                         // Ensure cost is not null
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }
        // Use consistent scale (e.g., 4) for amounts
        invoice.setDiscountAmount(totalDiscount.setScale(4, RoundingMode.HALF_UP));
         // Ensure preDiscountTotal reflects only non-hidden items if discounts apply per service
        // Re-calculate preDiscountTotal based on non-hidden items if not already done
         BigDecimal visiblePreDiscountTotal = lineItems.stream()
                                                      .filter(item -> !item.isHidden())
                                                      .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                                                      .reduce(BigDecimal.ZERO, BigDecimal::add);
        // It might be better to store both "raw total" and "visible total" on the Invoice entity
        // For now, let's assume preDiscountTotal *should* represent the total of visible items
        // If preDiscountTotal IS the raw total including hidden, the discount logic needs adjustment for 'ALL' case.
        // Assuming preDiscountTotal is the sum of VISIBLE items based on updateInvoice logic:
        invoice.setPreDiscountTotal(visiblePreDiscountTotal.setScale(4, RoundingMode.HALF_UP)); // Update preDiscountTotal too
        invoice.setAmount(invoice.getPreDiscountTotal().subtract(invoice.getDiscountAmount()).setScale(4, RoundingMode.HALF_UP));


    }


    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice finalizeInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));

         // Recalculate totals one last time before finalizing to ensure accuracy
         recalculateTotals(invoice);

        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        
        // Save the finalized invoice
        Invoice finalizedInvoice = invoiceRepository.save(invoice);

        // --- START: NEW EMAIL NOTIFICATION LOGIC ---
        try {
            Client client = finalizedInvoice.getClient();
            if (client == null) {
                 logger.warn("Cannot send invoice email: Invoice ID {} has no associated client.", finalizedInvoice.getId());
                 return finalizedInvoice; // Finalization succeeds, but no email
            }
    
            List<AppUser> users = appUserRepository.findByClientId(client.getId());
            if (users.isEmpty()) {
                logger.warn("No users found for client ID {}. Cannot send invoice email.", client.getId());
                 return finalizedInvoice; // Finalization succeeds
            }
    
            String subject = String.format("Your XamOps Invoice (%s) is Ready", finalizedInvoice.getInvoiceNumber());
            String text = String.format(
                "Hello,\n\nYour invoice (%s) for the billing period %s is now finalized and available for review.\n\n" +
                "Total Amount: $%.2f\n\n" +
                "You can view your invoice by logging into the XamOps portal.\n\n" +
                "Thank you,\nThe XamOps Team",
                finalizedInvoice.getInvoiceNumber(),
                finalizedInvoice.getBillingPeriod(),
                finalizedInvoice.getAmount() // Use the final amount
            );
    
            // Loop through all users associated with the client
            for (AppUser user : users) {
                if (user.getEmail() != null && !user.getEmail().isBlank()) {
                    // Send email only if the email field is not null or empty
                    logger.info("Sending finalized invoice email for invoice {} to user {} ({})", finalizedInvoice.getId(), user.getUsername(), user.getEmail());
                    emailService.sendSimpleMessage(user.getEmail(), subject, text);
                } else {
                     logger.warn("User {} (ID: {}) has no email address. Skipping invoice email.", user.getUsername(), user.getId());
                }
            }
        } catch (Exception e) {
            // CRITICAL: Log the error but DO NOT re-throw.
            // The request requires finalization to succeed even if email fails.
            logger.error("Failed to send invoice finalization email for Invoice ID {}. The invoice was still finalized.", finalizedInvoice.getId(), e);
        }
        // --- END: NEW EMAIL NOTIFICATION LOGIC ---

        return finalizedInvoice; // Return the saved invoice
    }

    // --- SOLUTION 1 IMPLEMENTATION ---
    @Transactional(readOnly = true)
    // Cache the DTO instead of the Entity
    @Cacheable(value = "invoiceDtos", key = "{#accountId, #year, #month}")
    public InvoiceDto getInvoiceForUser(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst();
        if (accountOpt.isEmpty()) {
            accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        }

        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found for identifier: " + accountId));


        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        logger.info("Searching for finalized invoice with parameters:");
        logger.info("--> Cloud Account DB ID: {}", cloudAccount.getId());
        logger.info("--> Billing Period: {}", billingPeriod);
        logger.info("--> Status: {}", Invoice.InvoiceStatus.FINALIZED);

        List<Invoice> invoices = invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(), billingPeriod, Invoice.InvoiceStatus.FINALIZED);

        if (invoices.isEmpty()) {
            logger.warn("--> RESULT: No finalized invoice found for the given criteria.");
            return null; // Return null DTO if not found
        }

        Invoice singleInvoice;
        if (invoices.size() > 1) {
            logger.warn("--> RESULT: Found {} finalized invoices for the same period. This is unexpected. Returning the most recent one (highest ID).", invoices.size());
            singleInvoice = invoices.stream()
                    .max(Comparator.comparing(Invoice::getId))
                     // Should not happen if list is not empty, but added for safety
                    .orElse(invoices.get(0));
        } else {
             singleInvoice = invoices.get(0);
        }


        logger.info("--> RESULT: Found finalized invoice with ID: {}", singleInvoice.getId());

         // ---- Eager Loading (Optional but can help prevent lazy loading issues during DTO conversion) ----
        // Uncomment these lines if you still encounter issues after switching to DTO caching.
        // try {
        //     Hibernate.initialize(singleInvoice.getClient());
        //     Hibernate.initialize(singleInvoice.getCloudAccount());
        //     Hibernate.initialize(singleInvoice.getLineItems());
        //     Hibernate.initialize(singleInvoice.getDiscounts());
        // } catch (Exception e) {
        //     logger.error("Error initializing lazy associations for Invoice ID {}: {}", singleInvoice.getId(), e.getMessage());
        //     // Handle error appropriately, maybe return null or throw a specific exception
        // }
         // ---- End Eager Loading ----


        // Convert to DTO *before* returning/caching
        return InvoiceDto.fromEntity(singleInvoice);
    }
    // --- END SOLUTION 1 IMPLEMENTATION ---

    // --- START: MODIFICATION FOR ADMIN CACHING FIX ---
    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "#invoiceId") // CHANGED: Cache DTO in "invoiceDtos" cache
    public InvoiceDto getInvoiceForAdmin(Long invoiceId) { // CHANGED: Return InvoiceDto
         // Use findById for direct ID lookup
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found with ID: " + invoiceId));

        // Convert to DTO *inside* the transactional method
        // This forces lazy-loading to occur before caching.
        return InvoiceDto.fromEntity(invoice);
    }
    // --- END: MODIFICATION FOR ADMIN CACHING FIX ---


    public ByteArrayInputStream generatePdfForInvoice(Long invoiceId) {
        // This method will now fail because getInvoiceForAdmin returns a DTO.
        // We must create a new *internal* method to get the raw entity for PDF generation.
        // Or, we can adapt the PDF generator to use the DTO. Let's adapt.
        InvoiceDto invoiceDto = getInvoiceForAdmin(invoiceId); // Fetches the cached DTO
        return generatePdfFromDto(invoiceDto); // Use the existing DTO-based PDF generator
    }

    public ByteArrayInputStream generatePdfFromDto(InvoiceDto dto) {
        // This method already works with DTO, no changes needed here regarding caching
        Invoice invoice = new Invoice(); // Create a temporary, detached entity for PDF generation
        invoice.setInvoiceNumber(dto.getInvoiceNumber());
        invoice.setBillingPeriod(dto.getBillingPeriod());
        invoice.setInvoiceDate(dto.getInvoiceDate());
        invoice.setPreDiscountTotal(dto.getPreDiscountTotal());
        invoice.setDiscountAmount(dto.getDiscountAmount());
        invoice.setAmount(dto.getAmount());

        CloudAccount tempAccount = new CloudAccount();
        tempAccount.setAccountName(dto.getAccountName());
        tempAccount.setAwsAccountId(dto.getAwsAccountId()); // Assuming DTO has AWS ID for PDF
         // If GCP ID is needed and available in DTO, add it here
        // tempAccount.setGcpProjectId(dto.getGcpProjectId());
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
                item.setHidden(itemDto.isHidden()); // Include hidden status if needed for PDF logic later
                 item.setInvoice(invoice); // Associate with temporary invoice
                return item;
            }).collect(Collectors.toList());
            invoice.setLineItems(lineItems);
        } else {
            invoice.setLineItems(new ArrayList<>());
        }

        // Add discounts if they exist in the DTO
        if (dto.getDiscounts() != null) {
            List<Discount> discounts = dto.getDiscounts().stream().map(discountDto -> {
                Discount discount = new Discount();
                discount.setId(discountDto.getId()); // Include ID if needed
                discount.setServiceName(discountDto.getServiceName());
                discount.setPercentage(discountDto.getPercentage());
                discount.setDescription(discountDto.getDescription());
                discount.setInvoice(invoice); // Associate with the temporary invoice
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
             // Safe access to potentially null CloudAccount
            CloudAccount account = invoice.getCloudAccount(); // Might be null if fetched lazily and not initialized
            String accountName = "N/A";
            String accountIdDisplay = "N/A"; // Display ID (AWS or GCP)

             if (account != null) {
                 accountName = Optional.ofNullable(account.getAccountName()).orElse("N/A");
                 if (account.getAwsAccountId() != null) {
                     accountIdDisplay = account.getAwsAccountId();
                 } else if (account.getGcpProjectId() != null) {
                     accountIdDisplay = account.getGcpProjectId();
                 }
             } else {
                  logger.warn("CloudAccount is null for Invoice ID {} during PDF generation.", invoice.getId());
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

             // Ensure line items list is not null
            List<InvoiceLineItem> lineItems = Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList());


            Map<String, List<InvoiceLineItem>> groupedByService = lineItems.stream()
                     // Filter out hidden items *before* grouping, if they shouldn't appear in PDF
                    .filter(item -> !item.isHidden())
                     .collect(Collectors.groupingBy(
                         item -> Optional.ofNullable(item.getServiceName()).orElse("Uncategorized") // Handle null service name
                     ));


            NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

            // Sort services alphabetically for consistent PDF output
             List<String> sortedServiceNames = groupedByService.keySet().stream().sorted().collect(Collectors.toList());


            // for (Map.Entry<String, List<InvoiceLineItem>> entry : groupedByService.entrySet()) {
             for (String serviceName : sortedServiceNames) {
                 List<InvoiceLineItem> itemsForService = groupedByService.get(serviceName);
                chargesTable.addCell(new Cell(1, 4).add(new Paragraph(serviceName).setBold()));

                 // Sort items within the service (e.g., by cost descending)
                 itemsForService.sort(Comparator.comparing(
                     (InvoiceLineItem item) -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO)
                 ).reversed());


                for (InvoiceLineItem item : itemsForService) {
                     // Optionally skip hidden items again if filtering wasn't done before grouping
                    // if (item.isHidden()) continue;

                    chargesTable.addCell(new Cell().add(new Paragraph("").setMarginLeft(15)).setBorder(null)); // Indentation
                    chargesTable.addCell(new Cell().add(new Paragraph(Optional.ofNullable(item.getResourceName()).orElse("N/A"))));
                    chargesTable.addCell(new Cell().add(new Paragraph(
                            Optional.ofNullable(item.getUsageQuantity()).orElse("0") + " " + Optional.ofNullable(item.getUnit()).orElse("")
                    )).setTextAlignment(TextAlignment.CENTER));
                    chargesTable.addCell(new Cell().add(new Paragraph(
                            currencyFormatter.format(Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                    )).setTextAlignment(TextAlignment.RIGHT));
                }


                 // Recalculate service total based only on items included in the loop (non-hidden)
                BigDecimal serviceTotal = itemsForService.stream()
                                               // .filter(item -> !item.isHidden()) // Already filtered before grouping
                                               .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                                               .reduce(BigDecimal.ZERO, BigDecimal::add);


                chargesTable.addCell(new Cell(1, 3).setBorder(null)); // Empty cells for alignment
                chargesTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(serviceTotal))).setBold().setTextAlignment(TextAlignment.RIGHT));

            }

            document.add(chargesTable);
            document.add(new Paragraph("\n"));

            Table totalsTable = new Table(UnitValue.createPercentArray(new float[]{3, 1}));
            totalsTable.setWidth(UnitValue.createPercentValue(40)).setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.RIGHT);

             // Handle potential null values for totals
            BigDecimal preDiscountTotal = Optional.ofNullable(invoice.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
            BigDecimal discountAmount = Optional.ofNullable(invoice.getDiscountAmount()).orElse(BigDecimal.ZERO);
            BigDecimal amount = Optional.ofNullable(invoice.getAmount()).orElse(BigDecimal.ZERO);


            totalsTable.addCell(new Cell().add(new Paragraph("Subtotal:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(preDiscountTotal))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));

             // Only show discount if it's non-zero
            if (discountAmount.compareTo(BigDecimal.ZERO) != 0) {
                totalsTable.addCell(new Cell().add(new Paragraph("Discounts:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
                totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(discountAmount.negate()))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            }


            totalsTable.addCell(new Cell().add(new Paragraph("Total:").setBold()).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(amount))).setBold().setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            document.add(totalsTable);

        } catch (Exception e) {
             logger.error("Error creating PDF for invoice {}: {}",
                          (invoice != null ? invoice.getId() : "UNKNOWN"), e.getMessage(), e);
            // Optionally rethrow or handle differently
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

         // --- More Robust Update Strategy ---
         // 1. Fetch existing line items for the invoice (consider a dedicated repository method)
        // Map existing items by a unique characteristic if possible (e.g., service+region+resource)
        // For simplicity, we'll still use clear() and add, assuming the DTO contains the *full* desired state.
         // If partial updates were needed, a merge strategy would be better.

         // Ensure the collection is initialized before clearing
         if (invoice.getLineItems() == null) {
             invoice.setLineItems(new ArrayList<>());
         } else {
            invoice.getLineItems().clear(); // Clear existing items linked to this invoice
         }
        // If using CascadeType.ALL and orphanRemoval=true, clearing should trigger deletion.
        // If not, you might need explicit deletion: invoiceLineItemRepository.deleteByInvoiceId(invoiceId);


        BigDecimal newPreDiscountTotal = BigDecimal.ZERO;

         // Ensure DTO list is not null
        List<InvoiceUpdateDto.LineItemUpdateDto> lineItemDtos = Optional.ofNullable(invoiceUpdateDto.getLineItems())
                                                                         .orElse(Collections.emptyList());

        for (InvoiceUpdateDto.LineItemUpdateDto itemDto : lineItemDtos) {
            InvoiceLineItem newLineItem = new InvoiceLineItem();
            newLineItem.setInvoice(invoice); // Re-associate with the parent invoice
            newLineItem.setServiceName(itemDto.getServiceName());
            newLineItem.setRegionName(itemDto.getRegionName());
            newLineItem.setResourceName(itemDto.getResourceName());
            newLineItem.setUsageQuantity(itemDto.getUsageQuantity());
            newLineItem.setUnit(itemDto.getUnit());
             // Ensure cost is not null
            BigDecimal itemCost = Optional.ofNullable(itemDto.getCost()).orElse(BigDecimal.ZERO);
            newLineItem.setCost(itemCost.setScale(4, RoundingMode.HALF_UP)); // Set scale

            newLineItem.setHidden(itemDto.isHidden());

            invoice.getLineItems().add(newLineItem); // Add the new item to the invoice's collection
             // Add non-hidden item costs to the total
            if (!itemDto.isHidden()) {
                newPreDiscountTotal = newPreDiscountTotal.add(itemCost);
            }
        }

        invoice.setPreDiscountTotal(newPreDiscountTotal.setScale(4, RoundingMode.HALF_UP)); // Set scale
        recalculateTotals(invoice); // Recalculate discount and final amount

        return invoiceRepository.save(invoice); // Save changes
    }


}