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
import com.xammer.billops.domain.*;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.GcpBillingDashboardDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xammer.billops.dto.InvoiceUpdateDto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    private static final Logger logger = LoggerFactory.getLogger(InvoiceManagementService.class);

    public InvoiceManagementService(InvoiceRepository invoiceRepository,
                                  CloudAccountRepository cloudAccountRepository,
                                  DiscountRepository discountRepository,
                                  BillingService billingService,
                                  GcpCostService gcpCostService) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.discountRepository = discountRepository;
        this.billingService = billingService;
        this.gcpCostService = gcpCostService;
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

        for (InvoiceDto.DiscountDto disc : invoiceDto.getDiscounts()) {
            BigDecimal percentage = disc.getPercentage().divide(new BigDecimal(100));

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(invoiceDto.getPreDiscountTotal().multiply(percentage));
            } else {
                BigDecimal serviceTotal = invoiceDto.getLineItems().stream()
                    .filter(item -> disc.getServiceName().equalsIgnoreCase(item.getServiceName()))
                    .map(InvoiceDto.LineItemDto::getCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }

        invoiceDto.setDiscountAmount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        invoiceDto.setAmount(invoiceDto.getPreDiscountTotal().subtract(invoiceDto.getDiscountAmount()));
    }


   @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Cloud account not found");
        }
        CloudAccount cloudAccount = accounts.get(0);

        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReport(Collections.singletonList(accountId), year, month);

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(cloudAccount.getClient());
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("TEMP-" + UUID.randomUUID().toString().toUpperCase().substring(0, 8));

        List<InvoiceLineItem> lineItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ServiceCostDetailDto serviceDto : detailedReport) {
            for (var regionDto : serviceDto.getRegionCosts()) {
                for (var resourceDto : regionDto.getResources()) {
                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(resourceDto.getResourceName());
                    lineItem.setUsageQuantity(String.format("%.3f", resourceDto.getQuantity()));
                    lineItem.setUnit(resourceDto.getUnit());
                    BigDecimal resourceCost = BigDecimal.valueOf(resourceDto.getCost());
                    lineItem.setCost(resourceCost);
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }

        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost);
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setAmount(totalCost);

        return invoice;
    }

    @Transactional
    public Invoice generateDraftInvoice(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst();
        if (accountOpt.isEmpty()) {
            accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        }

        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found"));

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();

        if ("GCP".equals(cloudAccount.getProvider())) {
            try {
                GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDto(accountId);

                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (GcpBillingDashboardDto.ServiceBreakdown service : dashboardDto.getServiceBreakdown()) {
                        double totalServiceCost = service.getAmount().doubleValue();

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
            detailedReport = billingService.getDetailedBillingReport(Collections.singletonList(accountId), year, month);
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
            for (var regionDto : serviceDto.getRegionCosts()) {
                for (var resourceDto : regionDto.getResources()) {
                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(resourceDto.getResourceName());
                    lineItem.setUsageQuantity(String.format("%.3f", resourceDto.getQuantity()));
                    lineItem.setUnit(resourceDto.getUnit());
                    BigDecimal resourceCost = BigDecimal.valueOf(resourceDto.getCost());
                    lineItem.setCost(resourceCost);
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(resourceCost);
                }
            }
        }

        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost);
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setAmount(totalCost);

        return invoiceRepository.save(invoice);
    }
    
@Transactional
public Invoice applyDiscountToInvoice(Long invoiceId, String serviceName, BigDecimal percentage) {
    Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new RuntimeException("Invoice not found"));

    if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
        throw new IllegalStateException("Cannot apply discount to a finalized invoice.");
    }

    Discount discount = new Discount();
    discount.setInvoice(invoice);
    discount.setClient(invoice.getClient());
    discount.setServiceName(serviceName);
    discount.setPercentage(percentage);
    discount.setDescription(String.format("%.2f%% discount for %s",
        percentage,
        "ALL".equalsIgnoreCase(serviceName) ? "Overall Bill" : serviceName
    ));
    
    discountRepository.save(discount);

    invoice.getDiscounts().add(discount);

    recalculateTotals(invoice);

    return invoiceRepository.save(invoice);
}
@Transactional
public Invoice removeDiscountFromInvoice(Long invoiceId, Long discountId) {
    Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new RuntimeException("Invoice not found"));

    if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
        throw new IllegalStateException("Cannot modify a finalized invoice.");
    }

    Optional<Discount> discountToRemove = invoice.getDiscounts().stream()
            .filter(d -> d.getId().equals(discountId))
            .findFirst();

    if (discountToRemove.isPresent()) {
        invoice.getDiscounts().remove(discountToRemove.get());
        recalculateTotals(invoice);
        return invoiceRepository.save(invoice);
    } else {
        throw new RuntimeException("Discount not found on this invoice.");
    }
}
    private void recalculateTotals(Invoice invoice) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (Discount disc : invoice.getDiscounts()) {
            BigDecimal percentage = disc.getPercentage().divide(new BigDecimal(100));

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(invoice.getPreDiscountTotal().multiply(percentage));
            } else {
                 BigDecimal serviceTotal = invoice.getLineItems().stream()
                    .filter(item -> disc.getServiceName().equalsIgnoreCase(item.getServiceName()))
                    .map(InvoiceLineItem::getCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(serviceTotal.multiply(percentage));
            }
        }

        invoice.setDiscountAmount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        invoice.setAmount(invoice.getPreDiscountTotal().subtract(invoice.getDiscountAmount()));
    }


    @Transactional
    public Invoice finalizeInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        return invoiceRepository.save(invoice);
    }

    @Transactional(readOnly = true)
    public Invoice getInvoiceForUser(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst();
        if (accountOpt.isEmpty()) {
            accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        }

        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found"));

        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        logger.info("Searching for finalized invoice with parameters:");
        logger.info("--> Cloud Account DB ID: {}", cloudAccount.getId());
        logger.info("--> Billing Period: {}", billingPeriod);
        logger.info("--> Status: {}", Invoice.InvoiceStatus.FINALIZED);

        List<Invoice> invoices = invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(), billingPeriod, Invoice.InvoiceStatus.FINALIZED);

        if (invoices.isEmpty()) {
            logger.warn("--> RESULT: No finalized invoice found for the given criteria.");
            return null;
        }

        if (invoices.size() > 1) {
            logger.warn("--> RESULT: Found {} finalized invoices for the same period. This is unexpected. Returning the most recent one (highest ID).", invoices.size());
            return invoices.stream()
                         .max(Comparator.comparing(Invoice::getId))
                         .orElse(null);
        }

        Invoice singleInvoice = invoices.get(0);
        logger.info("--> RESULT: Found finalized invoice with ID: {}", singleInvoice.getId());
        return singleInvoice;
    }
    
    @Transactional(readOnly = true)
    public Invoice getInvoiceForAdmin(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new RuntimeException("Invoice not found"));
    }

    public ByteArrayInputStream generatePdfForInvoice(Long invoiceId) {
        Invoice invoice = getInvoiceForAdmin(invoiceId);
        return createPdfStream(invoice);
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

        List<InvoiceLineItem> lineItems = dto.getLineItems().stream().map(itemDto -> {
            InvoiceLineItem item = new InvoiceLineItem();
            item.setServiceName(itemDto.getServiceName());
            item.setResourceName(itemDto.getResourceName());
            item.setRegionName(itemDto.getRegionName());
            item.setUsageQuantity(itemDto.getUsageQuantity());
            item.setUnit(itemDto.getUnit());
            item.setCost(itemDto.getCost());
            return item;
        }).collect(Collectors.toList());
        invoice.setLineItems(lineItems);

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
            headerTable.addCell(new Cell().add(new Paragraph("Account Name:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(invoice.getCloudAccount().getAccountName())).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Account ID:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(invoice.getCloudAccount().getAwsAccountId())).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Billing Period:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(invoice.getBillingPeriod())).setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph("Invoice Date:")).setBold().setBorder(null));
            headerTable.addCell(new Cell().add(new Paragraph(invoice.getInvoiceDate().toString())).setBorder(null));
            document.add(headerTable);
            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Charges by service").setFontSize(14).setBold());

            Table chargesTable = new Table(UnitValue.createPercentArray(new float[]{4, 3, 2, 2}));
            chargesTable.setWidth(UnitValue.createPercentValue(100));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Service")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Resource")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Usage")).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Amount (USD)")).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.RIGHT));

            Map<String, List<InvoiceLineItem>> groupedByService = invoice.getLineItems().stream()
                .collect(Collectors.groupingBy(InvoiceLineItem::getServiceName));

            NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

            for(Map.Entry<String, List<InvoiceLineItem>> entry : groupedByService.entrySet()) {
                chargesTable.addCell(new Cell(1, 4).add(new Paragraph(entry.getKey()).setBold()));

                for(InvoiceLineItem item : entry.getValue()) {
                    chargesTable.addCell(new Cell().add(new Paragraph("").setMarginLeft(15)).setBorder(null));
                    chargesTable.addCell(new Cell().add(new Paragraph(item.getResourceName())));
                    chargesTable.addCell(new Cell().add(new Paragraph(item.getUsageQuantity() + " " + item.getUnit())).setTextAlignment(TextAlignment.CENTER));
                    chargesTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(item.getCost()))).setTextAlignment(TextAlignment.RIGHT));
                }

                BigDecimal serviceTotal = entry.getValue().stream().map(InvoiceLineItem::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                chargesTable.addCell(new Cell(1, 3).setBorder(null));
                chargesTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(serviceTotal))).setBold().setTextAlignment(TextAlignment.RIGHT));
            }

            document.add(chargesTable);
            document.add(new Paragraph("\n"));

            Table totalsTable = new Table(UnitValue.createPercentArray(new float[]{3, 1}));
            totalsTable.setWidth(UnitValue.createPercentValue(40)).setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.RIGHT);

            totalsTable.addCell(new Cell().add(new Paragraph("Subtotal:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(invoice.getPreDiscountTotal()))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));

            totalsTable.addCell(new Cell().add(new Paragraph("Xammer's Credit:")).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(invoice.getDiscountAmount().negate()))).setTextAlignment(TextAlignment.RIGHT).setBorder(null));

            totalsTable.addCell(new Cell().add(new Paragraph("Total:").setBold()).setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(invoice.getAmount()))).setBold().setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            document.add(totalsTable);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

     @Transactional
    public Invoice updateInvoice(Long invoiceId, InvoiceUpdateDto invoiceUpdateDto) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot edit a finalized invoice.");
        }

        invoice.getLineItems().clear();
        BigDecimal newPreDiscountTotal = BigDecimal.ZERO;

        for (InvoiceUpdateDto.LineItemUpdateDto itemDto : invoiceUpdateDto.getLineItems()) {
            InvoiceLineItem newLineItem = new InvoiceLineItem();
            newLineItem.setInvoice(invoice);
            newLineItem.setServiceName(itemDto.getServiceName());
            newLineItem.setRegionName(itemDto.getRegionName());
            newLineItem.setResourceName(itemDto.getResourceName());
            newLineItem.setUsageQuantity(itemDto.getUsageQuantity());
            newLineItem.setUnit(itemDto.getUnit());
            newLineItem.setCost(itemDto.getCost());

            newLineItem.setHidden(itemDto.isHidden());

            invoice.getLineItems().add(newLineItem);
            newPreDiscountTotal = newPreDiscountTotal.add(itemDto.getCost());
        }

        invoice.setPreDiscountTotal(newPreDiscountTotal);
        recalculateTotals(invoice);

        return invoiceRepository.save(invoice);
    }
}