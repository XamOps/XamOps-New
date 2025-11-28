package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.xammer.billops.controller.AdminCloudFrontController;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.*;
import com.xammer.billops.repository.*;
import com.xammer.billops.service.CloudFrontUsageService.CloudFrontUsageDto;
import com.xammer.cloud.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InvoiceManagementService {

    // Company Information Constants
    private static final String COMPANY_NAME = "XAMMER TECHNOLOGIES PRIVATE LIMITED";
    private static final String COMPANY_ADDRESS = "2nd Floor, H.IN.KH.No 293, Western Marg,\nNear Kher Singh Estate, Saidulajab, New Delhi,\nSouth Delhi, Delhi, 110030";
    private static final String COMPANY_UDYAM = "UDYAM-DL-08-0100428 (Micro)";
    private static final String COMPANY_GSTIN = "07AAACX3428N1ZO";
    private static final String COMPANY_STATE = "Delhi";
    private static final String COMPANY_STATE_CODE = "07";
    private static final String COMPANY_CIN = "U72900UP20200PC135808";
    private static final String BANK_NAME = "HDFC BANK";
    private static final String BANK_ACC_NO = "50200056268509";
    private static final String BANK_IFSC = "HDFC0000329";
    private static final String COMPANY_PAN = "AAACX3428N";

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

    private static final String INVOICE_LIST_CACHE_KEY = "billops:invoices:list:admin";
    private static final long CACHE_TTL_MINUTES = 60;
    private static final BigDecimal IGST_RATE = new BigDecimal("0.18");

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

    private void evictInvoiceListCache() {
        redisCache.evict(INVOICE_LIST_CACHE_KEY);
        logger.info("Evicted invoice list cache: {}", INVOICE_LIST_CACHE_KEY);
    }

    // --- PDF GENERATION START ---
    
    public ByteArrayInputStream generatePdfForInvoice(Long invoiceId) {
        InvoiceDto invoiceDto = getInvoiceForAdmin(invoiceId);
        return generatePdfFromDto(invoiceDto);
    }

    public ByteArrayInputStream generatePdfFromDto(InvoiceDto dto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf, PageSize.A4)) {

            document.setMargins(20, 20, 20, 20);
            
            // 1. Header & Address Section (Combined Grid)
            addHeaderAndAddressSection(document, dto);

            // 2. Main Item Table
            BigDecimal taxableValue = addMainTable(document, dto);

            // 3. Tax Section
            BigDecimal taxAmount = dto.getTaxAmount() != null ? dto.getTaxAmount() : taxableValue.multiply(IGST_RATE);
            addTaxSection(document, taxableValue, taxAmount);

            // 4. Footer
            addFooter(document, taxableValue.add(taxAmount));

        } catch (Exception e) {
            logger.error("Error generating PDF", e);
            throw new RuntimeException("PDF generation failed", e);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    private void addHeaderAndAddressSection(Document document, InvoiceDto dto) {
        // Main Title
        Paragraph title = new Paragraph("Tax Invoice")
                .setBold().setFontSize(14).setTextAlignment(TextAlignment.CENTER).setMarginBottom(5);
        document.add(title);

        // Create the main grid table: 2 Columns (Left 50%, Right 50%)
        Table mainTable = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth();

        // --- FETCH CLIENT DETAILS FROM DB ---
        Client client = clientRepository.findById(dto.getClientId()).orElse(new Client("Unknown"));
        String clientName = dto.getClientName();
        String clientAddress = Optional.ofNullable(client.getAddress()).orElse("");
        String clientGstin = Optional.ofNullable(client.getGstin()).orElse("");
        String stateName = Optional.ofNullable(client.getStateName()).orElse("");
        String stateCode = Optional.ofNullable(client.getStateCode()).orElse("");

        // --- LEFT COLUMN (Seller & Consignee) ---
        Cell leftCell = new Cell().setPadding(0).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        
        // Seller Info
        Paragraph sellerInfo = new Paragraph()
                .add(new com.itextpdf.layout.element.Text(COMPANY_NAME).setBold().setFontSize(9))
                .add("\n" + COMPANY_ADDRESS)
                .add("\nUDYAM: " + COMPANY_UDYAM)
                .add("\nGSTIN/UIN: ").add(new com.itextpdf.layout.element.Text(COMPANY_GSTIN).setBold())
                .add("\nState Name: " + COMPANY_STATE + ", Code: " + COMPANY_STATE_CODE)
                .add("\nCIN: " + COMPANY_CIN)
                .setFontSize(8).setPadding(5);
        
        leftCell.add(sellerInfo);

        // Consignee (Ship To) - Separator line
        leftCell.add(new Paragraph("Consignee (Ship to)").setBold().setFontSize(8)
                .setBorderTop(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(5).setMargin(0));
        
        Paragraph consigneeInfo = new Paragraph()
                .add(new com.itextpdf.layout.element.Text(clientName).setBold().setFontSize(9));
        
        // Improved Logic: Only add lines if data exists to avoid empty "Code: " lines
        if (!clientAddress.isEmpty()) consigneeInfo.add("\n" + clientAddress);
        if (!clientGstin.isEmpty()) consigneeInfo.add("\nGSTIN/UIN: " + clientGstin);
        if (!stateName.isEmpty()) consigneeInfo.add("\nState Name: " + stateName + ", Code: " + stateCode);
                
        consigneeInfo.setFontSize(8).setPaddingLeft(5).setPaddingBottom(5);
        
        leftCell.add(consigneeInfo);
        mainTable.addCell(leftCell);

        // --- RIGHT COLUMN (Invoice Meta & Buyer) ---
        Cell rightCell = new Cell().setPadding(0).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        
        // Nested Table for Metadata Grid
        Table metaTable = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth();

        // Row 1
        addMetaCell(metaTable, "Invoice No.", dto.getInvoiceNumber());
        addMetaCell(metaTable, "Dated", dto.getInvoiceDate() != null ? dto.getInvoiceDate().format(DateTimeFormatter.ofPattern("dd-MMM-yy")) : "");

        // Row 2
        addMetaCell(metaTable, "Delivery Note", "");
        addMetaCell(metaTable, "Mode/Terms of Payment", "");

        // Row 3
        addMetaCell(metaTable, "Reference No. & Date", "");
        addMetaCell(metaTable, "Other References", "");

        // Row 4
        addMetaCell(metaTable, "Buyer's Order No.", "");
        addMetaCell(metaTable, "Dated", "");

        // Row 5
        addMetaCell(metaTable, "Dispatch Doc No.", "");
        addMetaCell(metaTable, "Delivery Note Date", "");

        // Row 6
        addMetaCell(metaTable, "Dispatched through", "");
        addMetaCell(metaTable, "Destination", "");
        
        // Row 7 (Terms of Delivery spans both)
        Cell termsCell = new Cell(1, 2).add(new Paragraph("Terms of Delivery").setBold().setFontSize(7))
                .setPadding(2).setBorderBottom(new SolidBorder(ColorConstants.BLACK, 0.5f));
        metaTable.addCell(termsCell);
        
        rightCell.add(metaTable);

        // Buyer (Bill To) - inside right column below metadata
        rightCell.add(new Paragraph("Buyer (Bill to)").setBold().setFontSize(8).setPadding(5).setMargin(0));
        
        Paragraph buyerInfo = new Paragraph()
                .add(new com.itextpdf.layout.element.Text(clientName).setBold().setFontSize(9));
        
        // Improved Logic: Only add lines if data exists
        if (!clientAddress.isEmpty()) buyerInfo.add("\n" + clientAddress);
        if (!clientGstin.isEmpty()) buyerInfo.add("\nGSTIN/UIN: " + clientGstin);
        if (!stateName.isEmpty()) {
            buyerInfo.add("\nState Name: " + stateName + ", Code: " + stateCode);
            buyerInfo.add("\nPlace of Supply: " + stateName);
        }

        buyerInfo.setFontSize(8).setPaddingLeft(5).setPaddingBottom(5);

        rightCell.add(buyerInfo);
        mainTable.addCell(rightCell);

        document.add(mainTable);
        document.add(new Paragraph("\n")); // Spacing
    }

    private void addMetaCell(Table table, String label, String value) {
        Cell cell = new Cell().setPadding(2).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        cell.add(new Paragraph(label).setBold().setFontSize(7));
        cell.add(new Paragraph(value).setFontSize(8));
        table.addCell(cell);
    }

    private BigDecimal addMainTable(Document document, InvoiceDto dto) {
        // Columns: SI No, Description, HSN/SAC, Quantity, Rate, Per, Disc %, Amount
        float[] colWidths = {5, 43, 10, 8, 12, 5, 7, 10};
        Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();
        
        String[] headers = {"SI No.", "Description of Services", "HSN/SAC", "Quantity", "Rate", "per", "Disc. %", "Amount"};
        for (String h : headers) {
            table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(8))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setBackgroundColor(ColorConstants.LIGHT_GRAY)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
        }

        List<InvoiceDto.LineItemDto> items = dto.getLineItems() != null ? dto.getLineItems() : Collections.emptyList();
        int slNo = 1;
        BigDecimal totalTaxable = BigDecimal.ZERO;

        Map<String, List<InvoiceDto.LineItemDto>> grouped = items.stream().collect(Collectors.groupingBy(item -> 
            "Amazon CloudFront".equalsIgnoreCase(item.getServiceName()) ? "CLOUDFRONT" : "STANDARD"
        ));
        
        String monthStr = formatMonth(dto.getBillingPeriod());

        // --- ROW 1: Standard Consumption (AWS or GCP) ---
        if (grouped.containsKey("STANDARD")) {
            List<InvoiceDto.LineItemDto> stdItems = grouped.get("STANDARD");
            BigDecimal stdTotal = stdItems.stream().map(InvoiceDto.LineItemDto::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal discountPercent = BigDecimal.ZERO;
            BigDecimal discountAmount = BigDecimal.ZERO;
            
            if (dto.getDiscounts() != null) {
                for (InvoiceDto.DiscountDto d : dto.getDiscounts()) {
                    // Check discount names more broadly if needed
                    if ("AWS Consumption Charge".equalsIgnoreCase(d.getServiceName()) || 
                        "Google Cloud Consumption".equalsIgnoreCase(d.getServiceName()) ||
                        "ALL".equalsIgnoreCase(d.getServiceName())) {
                         discountPercent = d.getPercentage();
                         BigDecimal pct = discountPercent.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
                         discountAmount = stdTotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);
                    }
                }
            }

            // Detect Provider for Text Labels
            boolean isGcp = dto.getGcpProjectId() != null && !dto.getGcpProjectId().equals("N/A") 
                            && (dto.getAwsAccountId() == null || dto.getAwsAccountId().equals("N/A"));
            
            String headerText = isGcp ? "Google Cloud Consumption" : "AWS Consumption Charge";
            String subHeaderText = isGcp ? "Google Cloud Platform Charges" : "Amazon Web Service Charges";
            String accountLabel = isGcp ? "Project ID: " + dto.getGcpProjectId() : "Account ID: " + dto.getAwsAccountId();

            StringBuilder desc = new StringBuilder();
            desc.append(headerText).append("\n");
            desc.append(subHeaderText).append("\n");
            desc.append("For the Month of ").append(monthStr).append("\n");
            desc.append(accountLabel).append("\n");
            
            BigDecimal finalAmount = stdTotal.subtract(discountAmount);
            
            if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                desc.append(String.format("Discount: %.2f%% ($%s - $%s)\n", 
                        discountPercent, stdTotal.toString(), discountAmount.toString()));
                desc.append("($").append(finalAmount).append(")");
            }

            addTableRow(table, slNo++, desc.toString(), "998315", finalAmount);
            totalTaxable = totalTaxable.add(finalAmount);
        }

        // --- ROW 2: CloudFront Consumption ---
        if (grouped.containsKey("CLOUDFRONT")) {
            BigDecimal cfTotal = grouped.get("CLOUDFRONT").stream().map(InvoiceDto.LineItemDto::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
            
            StringBuilder desc = new StringBuilder();
            desc.append("AWS Cloud front consumption\n");
            desc.append("For ").append(monthStr).append(" Month: $").append(cfTotal).append("\n");
            desc.append("($").append(cfTotal).append(")");

            addTableRow(table, slNo++, desc.toString(), "998315", cfTotal);
            totalTaxable = totalTaxable.add(cfTotal);
        }

        // Filler rows
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 8; j++) {
                table.addCell(new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setHeight(15));
            }
        }

        // Total Row
        Cell totalLabel = new Cell(1, 7).add(new Paragraph("Total").setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addCell(totalLabel);
        
        Cell totalValue = new Cell().add(new Paragraph(formatCurrency(totalTaxable)).setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addCell(totalValue);

        document.add(table);
        
        document.add(new Paragraph("\nAmount Chargeable (in words)\n" + convertNumberToWords(dto.getAmount()))
                .setFontSize(9).setItalic().setMarginLeft(5));

        return totalTaxable;
    }

    private void addTableRow(Table table, int si, String desc, String hsn, BigDecimal amt) {
        table.addCell(createCell(String.valueOf(si), TextAlignment.CENTER));
        table.addCell(createCell(desc, TextAlignment.LEFT));
        table.addCell(createCell(hsn, TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER)); 
        table.addCell(createCell("", TextAlignment.RIGHT));  
        table.addCell(createCell("", TextAlignment.CENTER)); 
        table.addCell(createCell("", TextAlignment.CENTER)); 
        table.addCell(createCell(formatCurrency(amt), TextAlignment.RIGHT));
    }
    
    private Cell createCell(String text, TextAlignment alignment) {
        return new Cell().add(new Paragraph(text).setFontSize(8))
                .setTextAlignment(alignment)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3);
    }

    private void addTaxSection(Document document, BigDecimal taxable, BigDecimal tax) {
        document.add(new Paragraph("\n"));
        
        float[] widths = {15, 20, 15, 25, 25};
        Table table = new Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth();
        
        String[] headers = {"HSN/SAC", "Taxable Value", "Rate", "IGST Amount", "Total Tax Amount"};
        for (String h : headers) {
            table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(8))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
        }

        table.addCell(createCell("998315", TextAlignment.LEFT));
        table.addCell(createCell(formatCurrency(taxable), TextAlignment.RIGHT));
        table.addCell(createCell("18%", TextAlignment.RIGHT));
        table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT));
        table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT));
        
        table.addCell(createCell("Total", TextAlignment.RIGHT).setBold());
        table.addCell(createCell(formatCurrency(taxable), TextAlignment.RIGHT).setBold());
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT).setBold());
        table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT).setBold());

        document.add(table);
        document.add(new Paragraph("Tax Amount (in words): " + convertNumberToWords(tax)).setFontSize(9).setMarginTop(5).setMarginLeft(5));
    }

    private void addFooter(Document document, BigDecimal total) {
        document.add(new Paragraph("\n"));
        
        Table footerTable = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth();
        
        Cell bankCell = new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(5);
        bankCell.add(new Paragraph("Company's Bank Details").setBold().setUnderline().setFontSize(9));
        bankCell.add(new Paragraph("A/c Holder's Name: " + COMPANY_NAME).setFontSize(8));
        bankCell.add(new Paragraph("Bank Name: " + BANK_NAME).setFontSize(8));
        bankCell.add(new Paragraph("A/c No.: " + BANK_ACC_NO).setFontSize(8));
        bankCell.add(new Paragraph("Branch & IFS Code: " + BANK_IFSC).setFontSize(8));
        
        Cell sigCell = new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(5)
                .setTextAlignment(TextAlignment.RIGHT)
                .setVerticalAlignment(VerticalAlignment.BOTTOM);
        sigCell.add(new Paragraph("for " + COMPANY_NAME).setBold().setFontSize(8));
        sigCell.add(new Paragraph("\n\n\n")); 
        sigCell.add(new Paragraph("Authorised Signatory").setFontSize(8));
        
        footerTable.addCell(bankCell);
        footerTable.addCell(sigCell);
        document.add(footerTable);
        
        document.add(new Paragraph("Company's PAN: " + COMPANY_PAN).setFontSize(8).setMarginTop(2));
        document.add(new Paragraph("\nDeclaration").setBold().setUnderline().setFontSize(8));
        document.add(new Paragraph("We declare that this invoice shows the actual price of the goods described and that all particulars are true and correct.").setFontSize(8));
        document.add(new Paragraph("This is a Computer Generated Invoice").setFontSize(8).setItalic().setTextAlignment(TextAlignment.CENTER).setMarginTop(5));
    }

    // --- END PDF GENERATION ---

    // --- HELPER METHODS ---

    public InvoiceDto applyDiscountToTemporaryInvoice(InvoiceDto invoiceDto, DiscountRequestDto discountRequest) {
        InvoiceDto.DiscountDto newDiscount = new InvoiceDto.DiscountDto();
        newDiscount.setServiceName(discountRequest.getServiceName());
        newDiscount.setPercentage(discountRequest.getPercentage());
        newDiscount.setDescription(String.format("%.2f%% discount for %s", discountRequest.getPercentage(), discountRequest.getServiceName()));
        if (invoiceDto.getDiscounts() == null) invoiceDto.setDiscounts(new ArrayList<>());
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
            } else if ("AWS Consumption Charge".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !"Amazon CloudFront".equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                totalDiscount = totalDiscount.add(standardTotal.multiply(percentage));
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
        invoiceDto.setDiscountAmount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        BigDecimal taxableValue = preDiscountTotal.subtract(totalDiscount);
        if (taxableValue.compareTo(BigDecimal.ZERO) < 0) taxableValue = BigDecimal.ZERO;
        BigDecimal taxAmount = taxableValue.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
        invoiceDto.setTaxAmount(taxAmount);
        invoiceDto.setAmount(taxableValue.add(taxAmount));
    }

    @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) throw new RuntimeException("Cloud account not found");
        CloudAccount cloudAccount = accounts.get(0);
        List<ServiceCostDetailDto> detailedReport = billingService.getDetailedBillingReport(Collections.singletonList(accountId), year, month, false);
        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        try { invoice.setClient(cloudAccount.getClient()); } catch (Exception e) {}
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
                if (resources.isEmpty() && regionDto.getCost() > 0) {
                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(serviceDto.getServiceName() + " Charges");
                    lineItem.setUsageQuantity("1");
                    lineItem.setUnit("N/A");
                    BigDecimal regionCost = BigDecimal.valueOf(regionDto.getCost());
                    lineItem.setCost(regionCost.setScale(4, RoundingMode.HALF_UP));
                    lineItems.add(lineItem);
                    totalCost = totalCost.add(regionCost);
                } else {
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
        }
        invoice.setLineItems(lineItems);
        invoice.setPreDiscountTotal(totalCost.setScale(4, RoundingMode.HALF_UP));
        invoice.setDiscountAmount(BigDecimal.ZERO);
        invoice.setAmount(totalCost.setScale(4, RoundingMode.HALF_UP));
        return invoice;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice generateDraftInvoice(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst();
        if (accountOpt.isEmpty()) accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Cloud account not found"));
        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();
        if ("GCP".equals(cloudAccount.getProvider())) {
            try {
                GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDtoAndCache(accountId).orElse(null);
                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (GcpBillingDashboardDto.ServiceBreakdown service : dashboardDto.getServiceBreakdown()) {
                        BigDecimal serviceAmount = Optional.ofNullable(service.getAmount()).orElse(BigDecimal.ZERO);
                        double totalServiceCost = serviceAmount.doubleValue();
                        if (totalServiceCost <= 0) continue;
                        ResourceCostDto serviceAsResource = new ResourceCostDto(service.getServiceName(), "Service-level total", totalServiceCost, 1.0, "N/A");
                        RegionCostDto globalRegion = new RegionCostDto("global", totalServiceCost, List.of(serviceAsResource));
                        ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto(service.getServiceName(), totalServiceCost, List.of(globalRegion));
                        detailedReport.add(serviceDetail);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to generate GCP invoice", e);
            }
        } else {
            detailedReport = billingService.getDetailedBillingReport(Collections.singletonList(accountId), year, month, false);
        }
        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(cloudAccount.getClient());
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber(UUID.randomUUID().toString().toUpperCase().substring(0, 12));
        List<InvoiceLineItem> lineItems = new ArrayList<>();
        for (ServiceCostDetailDto serviceDto : detailedReport) {
            List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts()).orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources()).orElse(Collections.emptyList());
                if (resources.isEmpty() && regionDto.getCost() > 0) {
                    InvoiceLineItem lineItem = new InvoiceLineItem();
                    lineItem.setInvoice(invoice);
                    lineItem.setServiceName(serviceDto.getServiceName());
                    lineItem.setRegionName(regionDto.getRegionName());
                    lineItem.setResourceName(serviceDto.getServiceName() + " Charges");
                    lineItem.setUsageQuantity("1");
                    lineItem.setUnit("N/A");
                    lineItem.setCost(BigDecimal.valueOf(regionDto.getCost()).setScale(4, RoundingMode.HALF_UP));
                    lineItems.add(lineItem);
                } else {
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
                    }
                }
            }
        }
        invoice.setLineItems(lineItems);
        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice applyDiscountToInvoice(Long invoiceId, String serviceName, BigDecimal percentage) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) throw new IllegalStateException("Cannot apply discount to finalized invoice.");
        
        Discount discount = new Discount();
        discount.setInvoice(invoice);
        if (invoice.getClient() != null) {
            try {
                Client managedClient = clientRepository.findById(invoice.getClient().getId()).orElse(invoice.getClient());
                discount.setClient(managedClient);
            } catch (Exception e) { discount.setClient(invoice.getClient()); }
        }
        discount.setServiceName(serviceName);
        discount.setPercentage(percentage.setScale(2, RoundingMode.HALF_UP));
        
        String desc = "";
        if ("ALL".equalsIgnoreCase(serviceName)) desc = "Overall Bill Discount";
        else if ("AWS Consumption Charge".equalsIgnoreCase(serviceName)) desc = "AWS Consumption Discount";
        else desc = serviceName + " Discount";
        discount.setDescription(String.format("%.2f%% %s", percentage, desc));

        if (invoice.getDiscounts() == null) invoice.setDiscounts(new ArrayList<>());
        boolean exists = false;
        for(Discount d : invoice.getDiscounts()) if (d == discount) exists = true;
        if(!exists) invoice.getDiscounts().add(discount);

        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice removeDiscountFromInvoice(Long invoiceId, Long discountId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new RuntimeException("Invoice not found"));
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) throw new IllegalStateException("Cannot modify finalized invoice");
        Optional<Discount> toRemove = invoice.getDiscounts().stream().filter(d -> d.getId().equals(discountId)).findFirst();
        if (toRemove.isPresent()) {
            invoice.getDiscounts().remove(toRemove.get());
            discountRepository.delete(toRemove.get());
            recalculateTotals(invoice);
            evictInvoiceListCache();
            return invoiceRepository.save(invoice);
        }
        return invoice;
    }

    private void recalculateTotals(Invoice invoice) {
        List<InvoiceLineItem> lineItems = Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList());
        BigDecimal preDiscountTotal = lineItems.stream()
                .filter(item -> !item.isHidden())
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        invoice.setPreDiscountTotal(preDiscountTotal.setScale(4, RoundingMode.HALF_UP));

        List<Discount> discounts = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList());
        BigDecimal totalDiscount = BigDecimal.ZERO;

        for (Discount disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                    .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                totalDiscount = totalDiscount.add(preDiscountTotal.multiply(percentage));

            } else if ("AWS Consumption Charge".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !"Amazon CloudFront".equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                totalDiscount = totalDiscount.add(standardTotal.multiply(percentage));
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

        BigDecimal taxableValue = preDiscountTotal.subtract(totalDiscount);
        if (taxableValue.compareTo(BigDecimal.ZERO) < 0) taxableValue = BigDecimal.ZERO;

        BigDecimal taxAmount = taxableValue.multiply(IGST_RATE).setScale(4, RoundingMode.HALF_UP);
        invoice.setTaxAmount(taxAmount);

        BigDecimal grandTotal = taxableValue.add(taxAmount).setScale(4, RoundingMode.HALF_UP);
        invoice.setAmount(grandTotal);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice finalizeInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new RuntimeException("Invoice not found"));
        recalculateTotals(invoice);
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        Invoice finalizedInvoice = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        try {
            Client client = finalizedInvoice.getClient();
            if (client != null) {
                List<AppUser> users = appUserRepository.findByClientId(client.getId());
                String subject = String.format("Your XamOps Invoice (%s) is Ready", finalizedInvoice.getInvoiceNumber());
                String text = String.format("Invoice %s is finalized. Total: %s", finalizedInvoice.getInvoiceNumber(), finalizedInvoice.getAmount());
                for (AppUser user : users) {
                    if (user.getEmail() != null) emailService.sendSimpleMessage(user.getEmail(), subject, text);
                }
            }
        } catch (Exception e) { logger.error("Failed to send email", e); }
        return finalizedInvoice;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "{#accountId, #year, #month}")
    public InvoiceDto getInvoiceForUser(String accountId, int year, int month) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst();
        if (accountOpt.isEmpty()) accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
        CloudAccount cloudAccount = accountOpt.orElseThrow(() -> new RuntimeException("Account not found"));
        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        List<Invoice> invoices = invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(), billingPeriod, Invoice.InvoiceStatus.FINALIZED);
        if (invoices.isEmpty()) return null;
        return InvoiceDto.fromEntity(invoices.get(0));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "#invoiceId")
    public InvoiceDto getInvoiceForAdmin(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(() -> new RuntimeException("Invoice not found"));
        return InvoiceDto.fromEntity(invoice);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice mergeDraftInvoices(Long targetInvoiceId, Long sourceInvoiceId) {
        Invoice target = invoiceRepository.findById(targetInvoiceId).orElseThrow();
        Invoice source = invoiceRepository.findById(sourceInvoiceId).orElseThrow();
        if (target.getStatus() != Invoice.InvoiceStatus.DRAFT || source.getStatus() != Invoice.InvoiceStatus.DRAFT) 
            throw new IllegalStateException("Both must be draft");

        List<Discount> targetDiscounts = Optional.ofNullable(target.getDiscounts()).orElse(Collections.emptyList());
        for (Discount discount : targetDiscounts) {
            if ("ALL".equalsIgnoreCase(discount.getServiceName())) {
                discount.setServiceName("AWS Consumption Charge");
                String desc = discount.getDescription();
                if (desc != null && desc.contains("Overall Bill")) {
                    discount.setDescription(desc.replace("Overall Bill", "AWS Consumption"));
                } else {
                    discount.setDescription(String.format("%.2f%% AWS Consumption Discount", discount.getPercentage()));
                }
            }
        }

        for (InvoiceLineItem item : new ArrayList<>(source.getLineItems())) {
            item.setInvoice(target);
            target.getLineItems().add(item);
        }
        source.getLineItems().clear();

        for (Discount discount : new ArrayList<>(source.getDiscounts())) {
            discount.setInvoice(target);
            target.getDiscounts().add(discount);
        }
        source.getDiscounts().clear();

        recalculateTotals(target);
        Invoice savedTarget = invoiceRepository.save(target);
        invoiceRepository.delete(source);
        evictInvoiceListCache();
        return savedTarget;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice updateInvoice(Long invoiceId, InvoiceUpdateDto invoiceUpdateDto) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) throw new IllegalStateException("Cannot edit finalized");
        if (invoice.getLineItems() == null) invoice.setLineItems(new ArrayList<>()); else invoice.getLineItems().clear();
        List<InvoiceUpdateDto.LineItemUpdateDto> lineItemDtos = Optional.ofNullable(invoiceUpdateDto.getLineItems()).orElse(Collections.emptyList());
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
        }
        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<List<InvoiceDto>> getCachedAllInvoices() {
        return redisCache.get(INVOICE_LIST_CACHE_KEY, new TypeReference<List<InvoiceDto>>() {});
    }

    @Transactional(readOnly = true)
    public List<InvoiceDto> getAllInvoicesAndCache() {
        List<Invoice> invoices = invoiceRepository.findAll();
        List<InvoiceDto> dtos = invoices.stream().map(InvoiceDto::fromEntity).collect(Collectors.toList());
        redisCache.put(INVOICE_LIST_CACHE_KEY, dtos, CACHE_TTL_MINUTES);
        return dtos;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice generateCloudFrontInvoice(String accountId, int year, int month, List<CloudFrontUsageDto> usageData) {
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst().orElseThrow();
        Client client = cloudAccount.getClient();
        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(client);
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("CF-" + UUID.randomUUID().toString().toUpperCase().substring(0, 10));
        List<InvoiceLineItem> lineItems = new ArrayList<>();
        for (CloudFrontUsageDto usage : usageData) {
            InvoiceLineItem item = new InvoiceLineItem();
            item.setInvoice(invoice);
            item.setServiceName("Amazon CloudFront");
            item.setRegionName(usage.getRegion());
            item.setResourceName(usage.getUsageType());
            item.setUsageQuantity(String.format("%.3f", usage.getQuantity()));
            item.setUnit(usage.getUnit());
            item.setCost(BigDecimal.valueOf(usage.getCost()).setScale(4, RoundingMode.HALF_UP));
            lineItems.add(item);
        }
        invoice.setLineItems(lineItems);
        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice updateInvoiceLineItems(Long invoiceId, List<AdminCloudFrontController.LineItemUpdate> updates) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) throw new RuntimeException("Cannot edit finalized");
        for (AdminCloudFrontController.LineItemUpdate update : updates) {
            InvoiceLineItem item = invoice.getLineItems().stream().filter(li -> li.getId().equals(update.id)).findFirst().orElseThrow();
            String[] parts = item.getUsageQuantity().split(" ");
            BigDecimal quantity;
            try { quantity = new BigDecimal(parts[0]); } catch (Exception e) { quantity = BigDecimal.ONE; }
            BigDecimal cost = update.unitRate.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
            item.setCost(cost);
        }
        recalculateTotals(invoice);
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        Invoice savedInvoice = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return savedInvoice;
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) return "0.00";
        return new DecimalFormat("#,##0.00").format(value);
    }

    private String formatMonth(String billingPeriod) {
        if (billingPeriod == null) return "";
        try {
            String[] parts = billingPeriod.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            return java.time.Month.of(month).name() + " " + year;
        } catch (Exception e) {
            return billingPeriod;
        }
    }

    private String convertNumberToWords(BigDecimal amount) {
        return "INR " + amount.setScale(2, RoundingMode.HALF_UP).toString() + " Only";
    }
}