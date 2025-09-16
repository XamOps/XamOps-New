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
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Discount;
import com.xammer.billops.domain.Invoice;
import com.xammer.billops.domain.InvoiceLineItem;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public InvoiceManagementService(InvoiceRepository invoiceRepository,
                                  CloudAccountRepository cloudAccountRepository,
                                  DiscountRepository discountRepository,
                                  BillingService billingService) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.discountRepository = discountRepository;
        this.billingService = billingService;
    }

    /**
     * MODIFIED: This now adds to a list of discounts and recalculates the total.
     */
    public InvoiceDto applyDiscountToTemporaryInvoice(InvoiceDto invoiceDto, DiscountRequestDto discountRequest) {
        // Add the new discount to the list
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

        // Recalculate totals based on the full list of discounts
        recalculateTotalsForDto(invoiceDto);

        return invoiceDto;
    }

    /**
     * NEW HELPER METHOD: Recalculates the total discount and final total for an InvoiceDto.
     */
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
        invoiceDto.setFinalTotal(invoiceDto.getPreDiscountTotal().subtract(invoiceDto.getDiscountAmount()));
    }


    @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found"));

        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReport(accountId, year, month);

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
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
        invoice.setFinalTotal(totalCost);
        invoice.setDiscounts(new ArrayList<>());

        return invoice;
    }

    @Transactional
    public Invoice generateDraftInvoice(String accountId, int year, int month) {
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found"));

        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReport(accountId, year, month);

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
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
        invoice.setFinalTotal(totalCost);
        invoice.setDiscounts(new ArrayList<>());

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
        discount.setServiceName(serviceName);
        discount.setPercentage(percentage);
        discount.setDescription(percentage + "% discount for " + serviceName);
        invoice.getDiscounts().add(discount);

        recalculateTotals(invoice);

        return invoiceRepository.save(invoice);
    }

    private void recalculateTotals(Invoice invoice) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (Discount disc : invoice.getDiscounts()) {
            BigDecimal serviceTotal = invoice.getLineItems().stream()
                .filter(item -> item.getServiceName().equalsIgnoreCase(disc.getServiceName()))
                .map(InvoiceLineItem::getCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            totalDiscount = totalDiscount.add(serviceTotal.multiply(disc.getPercentage().divide(new BigDecimal(100))));
        }

        invoice.setDiscountAmount(totalDiscount);
        invoice.setFinalTotal(invoice.getPreDiscountTotal().subtract(totalDiscount));
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
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found"));
        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(), billingPeriod, Invoice.InvoiceStatus.FINALIZED)
                .orElse(null);
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
        invoice.setFinalTotal(dto.getFinalTotal());

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
            totalsTable.addCell(new Cell().add(new Paragraph(currencyFormatter.format(invoice.getFinalTotal()))).setBold().setTextAlignment(TextAlignment.RIGHT).setBorder(null));
            document.add(totalsTable);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ByteArrayInputStream(out.toByteArray());
    }
}