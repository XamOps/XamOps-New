package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.xammer.billops.dto.azure.AzureBillingDashboardDto; // ✅ Import Azure DTO
import com.xammer.billops.repository.*;
import com.xammer.billops.service.CloudFrontUsageService.CloudFrontUsageDto;
import com.xammer.cloud.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final AzureCostService azureCostService; // ✅ Added AzureCostService
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;
    private static final Logger logger = LoggerFactory.getLogger(InvoiceManagementService.class);
    private final CloudFrontUsageService cloudFrontUsageService;
    private final CloudFrontPrivateRateRepository privateRateRepository;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache;
    private AccountAccessHelper accountAccessHelper;

    private static final String INVOICE_LIST_CACHE_KEY = "billops:invoices:list:admin";
    private static final long CACHE_TTL_MINUTES = 60;
    private static final BigDecimal IGST_RATE = new BigDecimal("0.18");

    public InvoiceManagementService(InvoiceRepository invoiceRepository,
            CloudAccountRepository cloudAccountRepository,
            DiscountRepository discountRepository,
            BillingService billingService,
            GcpCostService gcpCostService,
            AzureCostService azureCostService, // ✅ Injected AzureCostService
            AppUserRepository appUserRepository,
            EmailService emailService,
            CloudFrontUsageService cloudFrontUsageService,
            CloudFrontPrivateRateRepository privateRateRepository,
            ClientRepository clientRepository,
            RedisCacheService redisCache,
            AccountAccessHelper accountAccessHelper) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.discountRepository = discountRepository;
        this.billingService = billingService;
        this.gcpCostService = gcpCostService;
        this.azureCostService = azureCostService; // ✅ Assigned
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
        this.cloudFrontUsageService = cloudFrontUsageService;
        this.privateRateRepository = privateRateRepository;
        this.clientRepository = clientRepository;
        this.redisCache = redisCache;
        this.accountAccessHelper = accountAccessHelper;
    }

    @PostConstruct
    public void init() {
        logger.info("✅ InvoiceManagementService initialized");
    }

    // --- PDF GENERATION START ---
    public ByteArrayInputStream generatePdfForInvoice(Long invoiceId) {
        InvoiceDto invoiceDto = getInvoiceForAdmin(invoiceId);
        return generatePdfFromDto(invoiceDto);
    }

    public ByteArrayInputStream generatePdfFromDto(InvoiceDto dto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out); PdfDocument pdf = new PdfDocument(writer); Document document = new Document(pdf, PageSize.A4)) {

            document.setMargins(20, 20, 20, 20);

            // Determine GST type: intra-state (CGST+SGST) if client is in same state as seller (07 = Delhi)
            String sellerStateCode = COMPANY_GSTIN.substring(0, 2); // "07"
            String clientGstin = "";
            if (dto.getClientId() != null) {
                Client client = clientRepository.findById(dto.getClientId()).orElse(null);
                if (client != null) clientGstin = Optional.ofNullable(client.getGstin()).orElse("");
            }
            boolean isIntraState = !clientGstin.isEmpty() && clientGstin.startsWith(sellerStateCode);
            logger.info("[InvoiceGen] Client GSTIN={}, sellerState={}, isIntraState={}", clientGstin, sellerStateCode, isIntraState);

            // 1. Header & Address Section (Combined Grid)
            addHeaderAndAddressSection(document, dto);

            // 2. Fetch USD→INR exchange rate (live API → stored rate → hardcoded fallback)
            double exchangeRate = fetchUsdToInrRate(dto);

            // 3. Main Item Table (amounts shown in INR)
            BigDecimal taxableValueInr = addMainTable(document, dto, isIntraState, exchangeRate);

            // 4. Tax Section (computed in INR from the INR taxable value)
            BigDecimal taxAmountInr = taxableValueInr.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
            addTaxSection(document, taxableValueInr, taxAmountInr, isIntraState);

            // 5. Footer
            addFooter(document, taxableValueInr.add(taxAmountInr));

        } catch (Exception e) {
            logger.error("Error generating PDF", e);
            throw new RuntimeException("PDF generation failed", e);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * Fetches the USD→INR exchange rate for PDF invoice generation.
     * Priority: 1) CloudAccount stored rate → 2) Live currency API → 3) Hardcoded fallback.
     */
    private double fetchUsdToInrRate(InvoiceDto dto) {
        // 1. Try the stored rate on the CloudAccount
        try {
            Optional<CloudAccount> accountOpt = Optional.empty();
            String awsId = dto.getAwsAccountId();
            String gcpId = dto.getGcpProjectId();
            String azureId = dto.getAzureSubscriptionId();
            if (awsId != null && !awsId.equals("N/A")) {
                accountOpt = cloudAccountRepository.findByAwsAccountId(awsId).stream().findFirst();
            } else if (gcpId != null && !gcpId.equals("N/A")) {
                accountOpt = cloudAccountRepository.findByGcpProjectId(gcpId);
            } else if (azureId != null && !azureId.equals("N/A")) {
                accountOpt = cloudAccountRepository.findByAzureSubscriptionId(azureId);
            }
            if (accountOpt.isPresent() && accountOpt.get().getUsdExchangeRate() != null
                    && accountOpt.get().getUsdExchangeRate() > 1.0) {
                double stored = accountOpt.get().getUsdExchangeRate();
                logger.info("[InvoiceGen] Using stored USD→INR rate: {}", stored);
                return stored;
            }
        } catch (Exception e) {
            logger.warn("[InvoiceGen] Could not fetch stored exchange rate: {}", e.getMessage());
        }
        // 2. Try live currency APIs (fawazahmed0/exchange-api — free, no key, daily updates)
        // Primary: Cloudflare Workers (always today's rate, no CDN lag)
        // Fallback: jsDelivr CDN (may lag by ~1 day due to CDN caching)
        String[] rateEndpoints = {
            "https://latest.currency-api.pages.dev/v1/currencies/usd.min.json",
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json"
        };
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        for (String endpoint : rateEndpoints) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    double inrRate = root.path("usd").path("inr").asDouble(0);
                    if (inrRate > 1.0) {
                        String rateDate = root.path("date").asText("unknown");
                        logger.info("[InvoiceGen] Fetched USD→INR rate={} (date={}) from {}", inrRate, rateDate, endpoint);
                        return inrRate;
                    }
                }
            } catch (Exception e) {
                logger.warn("[InvoiceGen] Exchange rate endpoint {} failed: {}", endpoint, e.getMessage());
            }
        }
        // 3. Hardcoded fallback — update this value if the API is down for extended periods
        logger.warn("[InvoiceGen] All exchange rate APIs failed — using hardcoded fallback USD→INR rate: 86.0");
        return 86.0;
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
        if (!clientAddress.isEmpty()) {
            consigneeInfo.add("\n" + clientAddress);
        }
        if (!clientGstin.isEmpty()) {
            consigneeInfo.add("\nGSTIN/UIN: " + clientGstin);
        }
        if (!stateName.isEmpty()) {
            consigneeInfo.add("\nState Name: " + stateName + ", Code: " + stateCode);
        }

        consigneeInfo.setFontSize(8).setPaddingLeft(5).setPaddingBottom(5);

        leftCell.add(consigneeInfo);
        mainTable.addCell(leftCell);

        // --- RIGHT COLUMN (Invoice Meta & Buyer) ---
        Cell rightCell = new Cell().setPadding(0).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));

        // Nested Table for Metadata Grid
        Table metaTable = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth();

        // Row 1
        addMetaCell(metaTable, "Invoice No.", dto.getInvoiceNumber());
        addMetaCell(metaTable, "Dated",
                dto.getInvoiceDate() != null ? dto.getInvoiceDate().format(DateTimeFormatter.ofPattern("dd-MMM-yy"))
                : "");

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
        if (!clientAddress.isEmpty()) {
            buyerInfo.add("\n" + clientAddress);
        }
        if (!clientGstin.isEmpty()) {
            buyerInfo.add("\nGSTIN/UIN: " + clientGstin);
        }
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

    private BigDecimal addMainTable(Document document, InvoiceDto dto, boolean isIntraState, double exchangeRate) {
        // Columns: SI No, Description, HSN/SAC, Quantity, Rate, Per, Disc %, Amount
        float[] colWidths = {5, 43, 10, 8, 12, 5, 7, 10};
        Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();
        BigDecimal rate = BigDecimal.valueOf(exchangeRate);
        String rateStr = String.format("%.2f", exchangeRate);

        String[] headers = {"SI No.", "Description of Services", "HSN/SAC", "Quantity", "Rate", "per", "Disc. %",
            "Amount"};
        for (String h : headers) {
            table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(8))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setBackgroundColor(ColorConstants.LIGHT_GRAY)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
        }

        List<InvoiceDto.LineItemDto> items = dto.getLineItems() != null ? dto.getLineItems() : Collections.emptyList();
        int slNo = 1;
        BigDecimal totalTaxable = BigDecimal.ZERO;

        Map<String, List<InvoiceDto.LineItemDto>> grouped = items.stream().collect(Collectors.groupingBy(
                item -> "Amazon CloudFront".equalsIgnoreCase(item.getServiceName()) ? "CLOUDFRONT" : "STANDARD"));

        String monthStr = formatMonth(dto.getBillingPeriod());

        // --- ROW 1: Standard Consumption (AWS, GCP, or Azure) ---
        if (grouped.containsKey("STANDARD")) {
            List<InvoiceDto.LineItemDto> stdItems = grouped.get("STANDARD");
            BigDecimal stdTotal = stdItems.stream().map(InvoiceDto.LineItemDto::getCost).reduce(BigDecimal.ZERO,
                    BigDecimal::add);

            BigDecimal discountPercent = BigDecimal.ZERO;
            BigDecimal discountAmount = BigDecimal.ZERO;
            boolean showDiscountInDesc = false;

            if (dto.getDiscounts() != null) {
                for (InvoiceDto.DiscountDto d : dto.getDiscounts()) {
                    String svcName = Optional.ofNullable(d.getServiceName()).orElse("");
                    // Only show discount in description for global/consumption-level discounts
                    if ("ALL".equalsIgnoreCase(svcName)
                            || "AWS Consumption Charge".equalsIgnoreCase(svcName)
                            || "Google Cloud Consumption".equalsIgnoreCase(svcName)
                            || "Microsoft Azure Consumption".equalsIgnoreCase(svcName)) {
                        discountPercent = d.getPercentage();
                        BigDecimal pct = discountPercent.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
                        discountAmount = stdTotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);
                        showDiscountInDesc = true;
                    }
                }
            }
            // For per-service discounts (not global), use DTO's pre-computed discount total silently
            if (!showDiscountInDesc && dto.getDiscountAmount() != null
                    && dto.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                discountAmount = dto.getDiscountAmount();
            }

            // Detect Provider for Text Labels
            boolean isGcp = dto.getGcpProjectId() != null && !dto.getGcpProjectId().equals("N/A")
                    && (dto.getAwsAccountId() == null || dto.getAwsAccountId().equals("N/A"));
            boolean isAzure = dto.getAzureSubscriptionId() != null && !dto.getAzureSubscriptionId().equals("N/A")
                    && (dto.getAwsAccountId() == null || dto.getAwsAccountId().equals("N/A"));

            String headerText;
            String subHeaderText;
            String accountLabel;

            if (isGcp) {
                headerText = "Google Cloud Consumption";
                subHeaderText = "Google Cloud Platform Charges";
                accountLabel = "Project ID: " + dto.getGcpProjectId();
            } else if (isAzure) {
                headerText = "Microsoft Azure Consumption";
                subHeaderText = "Microsoft Azure Cloud Charges";
                accountLabel = "Subscription ID: " + dto.getAzureSubscriptionId();
            } else {
                headerText = "AWS Consumption Charge";
                subHeaderText = "Amazon Web Service Charges";
                accountLabel = "Account ID: " + dto.getAwsAccountId();
            }

            BigDecimal finalAmountUsd = stdTotal.subtract(discountAmount);
            BigDecimal finalAmountInr = finalAmountUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            Paragraph descPara = new Paragraph();
            descPara.add(new com.itextpdf.layout.element.Text(headerText).setBold().setFontSize(8));
            descPara.add(new com.itextpdf.layout.element.Text("\n" + subHeaderText).setItalic().setFontSize(8));
            descPara.add(new com.itextpdf.layout.element.Text("\nFor the Month of " + monthStr).setItalic().setFontSize(8));
            descPara.add(new com.itextpdf.layout.element.Text("\n" + accountLabel).setItalic().setFontSize(8));
            descPara.add(new com.itextpdf.layout.element.Text(
                    String.format("\n($%.2f @%s)", finalAmountUsd.doubleValue(), rateStr)).setItalic().setFontSize(8));
            if (showDiscountInDesc && discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                descPara.add(new com.itextpdf.layout.element.Text(
                        String.format("\nDiscount: %.2f%% (-$%.2f)", discountPercent.doubleValue(), discountAmount.doubleValue())
                ).setItalic().setFontSize(8));
            }

            addTableRow(table, slNo++, descPara, "998315", finalAmountInr);
            totalTaxable = totalTaxable.add(finalAmountInr);
        }

        // --- ROW 2: CloudFront Consumption ---
        if (grouped.containsKey("CLOUDFRONT")) {
            BigDecimal cfTotalUsd = grouped.get("CLOUDFRONT").stream().map(InvoiceDto.LineItemDto::getCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cfTotalInr = cfTotalUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            Paragraph cfDescPara = new Paragraph();
            cfDescPara.add(new com.itextpdf.layout.element.Text("AWS CloudFront Consumption").setBold().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text("\nAmazon CloudFront Charges").setItalic().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text("\nFor the Month of " + monthStr).setItalic().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text(
                    String.format("\n($%.2f @%s)", cfTotalUsd.doubleValue(), rateStr)).setItalic().setFontSize(8));

            addTableRow(table, slNo++, cfDescPara, "998315", cfTotalInr);
            totalTaxable = totalTaxable.add(cfTotalInr);
        }

        // Filler rows (2 blank rows for spacing)
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 8; j++) {
                table.addCell(new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setHeight(15));
            }
        }

        // Tax rows: CGST+SGST for intra-state, IGST for inter-state
        BigDecimal halfTax = totalTaxable.multiply(new BigDecimal("0.09")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstAmount;
        if (isIntraState) {
            // CGST@9%
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("CGST@9%", TextAlignment.LEFT).setBold());
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("9 %", TextAlignment.RIGHT));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            // SGST@9%
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("SGST@9%", TextAlignment.LEFT).setBold());
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("9 %", TextAlignment.RIGHT));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            igstAmount = halfTax.add(halfTax);
        } else {
            // IGST@18%
            igstAmount = totalTaxable.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("IGST@18%", TextAlignment.LEFT).setBold());
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("18 %", TextAlignment.RIGHT));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell(formatCurrency(igstAmount), TextAlignment.RIGHT));
        }

        // Round Off row
        BigDecimal grossTotal = totalTaxable.add(igstAmount);
        BigDecimal roundedTotal = grossTotal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal roundOff = roundedTotal.subtract(grossTotal);
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("Round Off", TextAlignment.LEFT));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell(formatCurrency(roundOff.abs()), TextAlignment.RIGHT));

        // Total Row (shows taxable + IGST + roundoff)
        Cell totalLabel = new Cell(1, 7).add(new Paragraph("Total").setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addCell(totalLabel);

        Cell totalValue = new Cell().add(new Paragraph("\u20B9 " + formatCurrency(roundedTotal)).setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        table.addCell(totalValue);

        document.add(table);

        // Bill Details section (standard accounting entry)
        document.add(new Paragraph("Bill Details:").setBold().setFontSize(8).setMarginLeft(5).setMarginTop(2));
        document.add(new Paragraph("New Ref " + dto.getInvoiceNumber() + "    " + formatCurrency(roundedTotal) + " Dr")
                .setFontSize(8).setMarginLeft(15).setMarginBottom(2));

        // Amount Chargeable — use roundedTotal so it matches the Total row
        document.add(new Paragraph("Amount Chargeable (in words)    E. & O.E")
                .setFontSize(9).setMarginLeft(5).setMarginTop(3));
        document.add(new Paragraph(convertNumberToWords(roundedTotal))
                .setFontSize(9).setBold().setMarginLeft(5).setMarginBottom(3));

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

    private void addTableRow(Table table, int si, Paragraph descPara, String hsn, BigDecimal amt) {
        table.addCell(createCell(String.valueOf(si), TextAlignment.CENTER));
        table.addCell(new Cell().add(descPara).setTextAlignment(TextAlignment.LEFT)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
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

    private void addTaxSection(Document document, BigDecimal taxable, BigDecimal tax, boolean isIntraState) {
        document.add(new Paragraph("\n"));

        if (isIntraState) {
            // CGST 9% + SGST 9% table (7 columns)
            BigDecimal halfTax = tax.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            float[] widths = {12, 16, 9, 15, 9, 15, 24};
            Table table = new Table(UnitValue.createPercentArray(widths)).useAllAvailableWidth();
            String[] headers = {"HSN/SAC", "Taxable Value", "CGST Rate", "CGST Amount", "SGST Rate", "SGST Amount", "Total Tax Amount"};
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(7))
                        .setTextAlignment(TextAlignment.CENTER)
                        .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
            }
            // Data row
            table.addCell(createCell("998315", TextAlignment.LEFT));
            table.addCell(createCell(formatCurrency(taxable), TextAlignment.RIGHT));
            table.addCell(createCell("9%", TextAlignment.RIGHT));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            table.addCell(createCell("9%", TextAlignment.RIGHT));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT));
            // Total row
            table.addCell(createCell("Total", TextAlignment.RIGHT).setBold());
            table.addCell(createCell(formatCurrency(taxable), TextAlignment.RIGHT).setBold());
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT).setBold());
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT).setBold());
            table.addCell(createCell(formatCurrency(tax), TextAlignment.RIGHT).setBold());
            document.add(table);
        } else {
            // IGST 18% table (5 columns)
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
        }

        document.add(new Paragraph("Tax Amount (in words): " + convertNumberToWords(tax)).setFontSize(9).setMarginTop(5)
                .setMarginLeft(5));
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
        bankCell.add(new Paragraph("SWIFT Code: HDFCINB").setFontSize(8));

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
        document.add(new Paragraph(
                "We declare that this invoice shows the actual price of the goods described and that all particulars are true and correct.")
                .setFontSize(8));
        document.add(new Paragraph("This is a Computer Generated Invoice").setFontSize(8).setItalic()
                .setTextAlignment(TextAlignment.CENTER).setMarginTop(5));
    }

    // --- END PDF GENERATION ---
    // --- HELPER METHODS ---
    public InvoiceDto applyDiscountToTemporaryInvoice(InvoiceDto invoiceDto, DiscountRequestDto discountRequest) {
        InvoiceDto.DiscountDto newDiscount = new InvoiceDto.DiscountDto();
        newDiscount.setServiceName(discountRequest.getServiceName());
        newDiscount.setPercentage(discountRequest.getPercentage());
        newDiscount.setDescription(String.format("%.2f%% discount for %s", discountRequest.getPercentage(),
                discountRequest.getServiceName()));
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
        List<InvoiceDto.DiscountDto> discounts = Optional.ofNullable(invoiceDto.getDiscounts())
                .orElse(Collections.emptyList());
        List<InvoiceDto.LineItemDto> lineItems = Optional.ofNullable(invoiceDto.getLineItems())
                .orElse(Collections.emptyList());

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
        if (taxableValue.compareTo(BigDecimal.ZERO) < 0) {
            taxableValue = BigDecimal.ZERO;
        }
        BigDecimal taxAmount = taxableValue.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
        invoiceDto.setTaxAmount(taxAmount);
        invoiceDto.setAmount(taxableValue.add(taxAmount));
    }

    @Transactional(readOnly = true)
    public Invoice generateTemporaryInvoiceForUser(String accountId, int year, int month) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Cloud account not found");
        }
        CloudAccount cloudAccount = accounts.get(0);
        List<ServiceCostDetailDto> detailedReport = billingService
                .getDetailedBillingReport(Collections.singletonList(accountId), year, month, false);
        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        try {
            invoice.setClient(cloudAccount.getClient());
        } catch (Exception e) {
        }
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber("TEMP-" + UUID.randomUUID().toString().toUpperCase().substring(0, 8));
        List<InvoiceLineItem> lineItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        for (ServiceCostDetailDto serviceDto : detailedReport) {
            List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts())
                    .orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources())
                        .orElse(Collections.emptyList());
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
                        lineItem.setUsageQuantity(
                                String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0)));
                        lineItem.setUnit(resourceDto.getUnit());
                        BigDecimal resourceCost = BigDecimal
                                .valueOf(Optional.ofNullable(resourceDto.getCost()).orElse(0.0));
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
        logger.info("[InvoiceGen] generateDraftInvoice called: accountId='{}', year={}, month={}", accountId, year, month);
        // ✅ 1. Find Account by ANY Provider ID (AWS, GCP, or Azure)
        CloudAccount cloudAccount = cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found with ID: " + accountId));

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();

        // ✅ 2. Handle Logic Based on Provider
        if ("GCP".equals(cloudAccount.getProvider())) {
            try {
                GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDtoAndCache(accountId)
                        .orElse(null);
                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (GcpBillingDashboardDto.ServiceBreakdown service : dashboardDto.getServiceBreakdown()) {
                        BigDecimal serviceAmount = Optional.ofNullable(service.getAmount()).orElse(BigDecimal.ZERO);
                        double totalServiceCost = serviceAmount.doubleValue();
                        if (totalServiceCost <= 0) {
                            continue;
                        }

                        ResourceCostDto serviceAsResource = new ResourceCostDto(service.getServiceName(),
                                "Service-level total", totalServiceCost, 1.0, "N/A");

                        RegionCostDto globalRegion = new RegionCostDto();
                        globalRegion.setRegionName("global");
                        globalRegion.setCost(totalServiceCost);
                        globalRegion.setResources(List.of(serviceAsResource));

                        ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto();
                        serviceDetail.setServiceName(service.getServiceName());
                        serviceDetail.setCost(totalServiceCost);
                        serviceDetail.setRegionCosts(List.of(globalRegion));

                        detailedReport.add(serviceDetail);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to generate GCP invoice", e);
            }
        } else if ("Azure".equals(cloudAccount.getProvider())) {
            // ✅ 3. Azure Invoice Generation Logic
            try {
                // Fetch data using the AzureCostService
                AzureBillingDashboardDto dashboardDto = azureCostService.getBillingDashboard(accountId, year, month);

                if (dashboardDto != null && dashboardDto.getServiceBreakdown() != null) {
                    for (var service : dashboardDto.getServiceBreakdown()) {
                        double amount = service.getAmount() != null ? service.getAmount() : 0.0;
                        if (amount <= 0) {
                            continue;
                        }

                        // Map Azure service breakdown to generic DTO structure for consistent line item
                        // creation
                        ResourceCostDto serviceAsResource = new ResourceCostDto(service.getServiceName(),
                                "Service Cost", amount, 1.0, "N/A");

                        RegionCostDto globalRegion = new RegionCostDto();
                        globalRegion.setRegionName("Global");
                        globalRegion.setCost(amount);
                        globalRegion.setResources(List.of(serviceAsResource));

                        ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto();
                        serviceDetail.setServiceName(service.getServiceName());
                        serviceDetail.setCost(amount);
                        serviceDetail.setRegionCosts(List.of(globalRegion));

                        detailedReport.add(serviceDetail);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to generate Azure invoice", e);
            }
        } else {
            // AWS billing:
            //   getCostByDimension (GROUP BY SERVICE) only returns directly visible charges.
            //   getCostHistory (LINKED_ACCOUNT filter, no GROUP BY) returns the correct
            //   consolidated monthly total.
            //   getDetailedBillingReport provides per-region/resource breakdown matching
            //   the billing page.  We scale all costs proportionally so the total matches
            //   the getCostHistory consolidated amount.

            // Step 1: get correct consolidated total from getCostHistory
            BillingDashboardDto summaryDto = billingService.getBillingData(
                    Collections.singletonList(accountId), year, month, true);

            double consolidatedTotal = 0.0;
            if (summaryDto != null && summaryDto.getCostHistory() != null) {
                String targetMonthPrefix = String.format("%d-%02d", year, month);
                consolidatedTotal = summaryDto.getCostHistory().stream()
                        .filter(h -> h.getDate() != null && h.getDate().startsWith(targetMonthPrefix))
                        .mapToDouble(BillingDashboardDto.CostHistory::getCost)
                        .findFirst()
                        .orElse(0.0);
            }
            logger.info("[InvoiceGen] getCostHistory consolidated total for {}/{}: ${}",
                    year, month, String.format("%.2f", consolidatedTotal));

            // Step 2: get full per-region/resource breakdown (same data as billing page)
            List<ServiceCostDetailDto> detailedData = billingService.getDetailedBillingReport(
                    Collections.singletonList(accountId), year, month, true);

            if (consolidatedTotal > 0 && detailedData != null && !detailedData.isEmpty()) {
                // Scale all costs so they sum to the correct consolidated total
                double detailTotal = detailedData.stream()
                        .mapToDouble(ServiceCostDetailDto::getTotalCost).sum();
                double scaleFactor = detailTotal > 0 ? consolidatedTotal / detailTotal : 1.0;
                logger.info("[InvoiceGen] Detailed report total=${}, scaleFactor={}",
                        String.format("%.2f", detailTotal), String.format("%.4f", scaleFactor));

                for (ServiceCostDetailDto svc : detailedData) {
                    if (svc.getRegionCosts() != null) {
                        for (RegionCostDto region : svc.getRegionCosts()) {
                            region.setCost(region.getCost() * scaleFactor);
                            if (region.getResources() != null) {
                                for (ResourceCostDto resource : region.getResources()) {
                                    resource.setCost(resource.getCost() * scaleFactor);
                                }
                            }
                        }
                    }
                    svc.setCost(svc.getCost() * scaleFactor);
                    detailedReport.add(svc);
                }
            } else if (consolidatedTotal > 0) {
                // No detailed breakdown available — emit one consolidated line item
                ResourceCostDto awsCharges = new ResourceCostDto(
                        "Amazon Web Services", "AWS Cloud Services Charges", 1.0, consolidatedTotal, "N/A");
                RegionCostDto globalRegion = new RegionCostDto();
                globalRegion.setRegionName("Global");
                globalRegion.setCost(consolidatedTotal);
                globalRegion.setResources(List.of(awsCharges));
                ServiceCostDetailDto serviceDetail = new ServiceCostDetailDto();
                serviceDetail.setServiceName("Amazon Web Services");
                serviceDetail.setCost(consolidatedTotal);
                serviceDetail.setRegionCosts(List.of(globalRegion));
                detailedReport.add(serviceDetail);
            }
        }

        Invoice invoice = new Invoice();
        invoice.setCloudAccount(cloudAccount);
        invoice.setClient(cloudAccount.getClient());
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setBillingPeriod(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setInvoiceNumber(UUID.randomUUID().toString().toUpperCase().substring(0, 12));

        List<InvoiceLineItem> lineItems = new ArrayList<>();

        // ✅ 4. Common Line Item Creation Logic
        for (ServiceCostDetailDto serviceDto : detailedReport) {
            List<RegionCostDto> regionCosts = Optional.ofNullable(serviceDto.getRegionCosts())
                    .orElse(Collections.emptyList());
            for (var regionDto : regionCosts) {
                List<ResourceCostDto> resources = Optional.ofNullable(regionDto.getResources())
                        .orElse(Collections.emptyList());
                if (resources.isEmpty() && regionDto.getCost() > 0) {
                    // Fallback for region-only costs
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
                        if (costValue <= 0) {
                            continue;
                        }
                        InvoiceLineItem lineItem = new InvoiceLineItem();
                        lineItem.setInvoice(invoice);
                        lineItem.setServiceName(serviceDto.getServiceName());
                        lineItem.setRegionName(regionDto.getRegionName());
                        lineItem.setResourceName(resourceDto.getResourceName());
                        lineItem.setUsageQuantity(
                                String.format("%.3f", Optional.ofNullable(resourceDto.getQuantity()).orElse(0.0)));
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
        logger.info("[InvoiceGen] Draft invoice computed for account='{}' {}/{}: {} line items, total={}",
                accountId, year, month, lineItems.size(), invoice.getAmount());
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice applyDiscountToInvoice(Long invoiceId, String serviceName, BigDecimal percentage) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot apply discount to finalized invoice.");
        }

        Discount discount = new Discount();
        discount.setInvoice(invoice);
        if (invoice.getClient() != null) {
            try {
                Client managedClient = clientRepository.findById(invoice.getClient().getId())
                        .orElse(invoice.getClient());
                discount.setClient(managedClient);
            } catch (Exception e) {
                discount.setClient(invoice.getClient());
            }
        }
        discount.setServiceName(serviceName);
        discount.setPercentage(percentage.setScale(2, RoundingMode.HALF_UP));

        String desc = "";
        if ("ALL".equalsIgnoreCase(serviceName)) {
            desc = "Overall Bill Discount"; 
        }else if ("AWS Consumption Charge".equalsIgnoreCase(serviceName)) {
            desc = "AWS Consumption Discount"; 
        }else {
            desc = serviceName + " Discount";
        }
        discount.setDescription(String.format("%.2f%% %s", percentage, desc));

        if (invoice.getDiscounts() == null) {
            invoice.setDiscounts(new ArrayList<>());
        }
        boolean exists = false;
        for (Discount d : invoice.getDiscounts()) {
            if (d == discount) {
                exists = true;
            }
        }
        if (!exists) {
            invoice.getDiscounts().add(discount);
        }

        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public void deleteInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot delete a finalized invoice.");
        }
        invoiceRepository.delete(invoice);
        evictInvoiceListCache();
        logger.info("[InvoiceMgmt] Deleted {} invoice id={}", invoice.getStatus(), invoiceId);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice removeDiscountFromInvoice(Long invoiceId, Long discountId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot modify finalized invoice");
        }
        Optional<Discount> toRemove = invoice.getDiscounts().stream().filter(d -> d.getId().equals(discountId))
                .findFirst();
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
        if (taxableValue.compareTo(BigDecimal.ZERO) < 0) {
            taxableValue = BigDecimal.ZERO;
        }

        BigDecimal taxAmount = taxableValue.multiply(IGST_RATE).setScale(4, RoundingMode.HALF_UP);
        invoice.setTaxAmount(taxAmount);

        BigDecimal grandTotal = taxableValue.add(taxAmount).setScale(4, RoundingMode.HALF_UP);
        invoice.setAmount(grandTotal);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice finalizeInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        recalculateTotals(invoice);
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        Invoice finalizedInvoice = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        try {
            Client client = finalizedInvoice.getClient();
            if (client != null) {
                List<AppUser> users = appUserRepository.findByClientId(client.getId());
                String subject = String.format("Your XamOps Invoice (%s) is Ready",
                        finalizedInvoice.getInvoiceNumber());
                String text = String.format("Invoice %s is finalized. Total: %s", finalizedInvoice.getInvoiceNumber(),
                        finalizedInvoice.getAmount());
                for (AppUser user : users) {
                    if (user.getEmail() != null) {
                        emailService.sendSimpleMessage(user.getEmail(), subject, text);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to send email", e);
        }
        return finalizedInvoice;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "{#accountId, #year, #month}")
    public InvoiceDto getInvoiceForUser(String accountId, int year, int month) {
        // ✅ Updated Lookup: Find by AWS, GCP, or Azure ID to support all providers
        Optional<CloudAccount> accountOpt = cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId);

        CloudAccount cloudAccount = accountOpt
                .orElseThrow(() -> new RuntimeException("Account not found for ID: " + accountId));

        String billingPeriod = YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        List<Invoice> invoices = invoiceRepository.findByCloudAccountIdAndBillingPeriodAndStatus(cloudAccount.getId(),
                billingPeriod, Invoice.InvoiceStatus.FINALIZED);
        if (invoices.isEmpty()) {
            return null;
        }
        return InvoiceDto.fromEntity(invoices.get(0));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "invoiceDtos", key = "#invoiceId")
    public InvoiceDto getInvoiceForAdmin(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        return InvoiceDto.fromEntity(invoice);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice mergeDraftInvoices(Long targetInvoiceId, Long sourceInvoiceId) {
        Invoice target = invoiceRepository.findById(targetInvoiceId).orElseThrow();
        Invoice source = invoiceRepository.findById(sourceInvoiceId).orElseThrow();
        if (target.getStatus() != Invoice.InvoiceStatus.DRAFT || source.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Both must be draft");
        }

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
        if (invoice.getStatus() == Invoice.InvoiceStatus.FINALIZED) {
            throw new IllegalStateException("Cannot edit finalized");
        }
        if (invoice.getLineItems() == null) {
            invoice.setLineItems(new ArrayList<>()); 
        }else {
            invoice.getLineItems().clear();
        }
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
        }
        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    private String getTenantCacheKey(String key) {
        String tenantId = com.xammer.billops.config.multitenancy.TenantContext.getCurrentTenant();
        if (tenantId == null) {
            tenantId = "default";
        }
        return key + ":" + tenantId;
    }

    private void evictInvoiceListCache() {
        String cacheKey = getTenantCacheKey(INVOICE_LIST_CACHE_KEY);
        redisCache.evict(cacheKey);
        logger.info("Evicted invoice list cache: {}", cacheKey);
    }

    @Transactional(readOnly = true)
    public Optional<List<InvoiceDto>> getCachedAllInvoices() {
        String cacheKey = getTenantCacheKey(INVOICE_LIST_CACHE_KEY);
        Optional<List<InvoiceDto>> cached = redisCache.get(cacheKey, new TypeReference<List<InvoiceDto>>() {});
        // Apply per-user account filter on the cached full list before returning
        return cached.map(this::applyInvoiceFilter);
    }

    @Transactional(readOnly = true)
    public List<InvoiceDto> getAllInvoicesAndCache() {
        List<Invoice> invoices = invoiceRepository.findAll();
        long now = System.currentTimeMillis();
        List<InvoiceDto> dtos = invoices.stream().map(invoice -> {
            InvoiceDto dto = InvoiceDto.fromEntity(invoice);
            dto.setLastUpdated(now);
            return dto;
        }).collect(Collectors.toList());

        // Cache the FULL unfiltered list so all users share the same warm cache
        String cacheKey = getTenantCacheKey(INVOICE_LIST_CACHE_KEY);
        redisCache.put(cacheKey, dtos, CACHE_TTL_MINUTES);

        // Return filtered view for the current user
        return applyInvoiceFilter(dtos);
    }

    /**
     * Filters invoices to only those whose CloudAccount the current sub-user is allowed to see.
     * Primary admins (createdBy == null) see all invoices unfiltered.
     */
    private List<InvoiceDto> applyInvoiceFilter(List<InvoiceDto> invoices) {
        java.util.Set<Long> allowedDbIds = accountAccessHelper.getAllowedDbIds();
        if (allowedDbIds == null) return invoices; // Primary admin — no restriction
        if (allowedDbIds.isEmpty()) return Collections.emptyList();
        return invoices.stream()
                .filter(dto -> dto.getCloudAccountId() != null && allowedDbIds.contains(dto.getCloudAccountId()))
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice generateCloudFrontInvoice(String accountId, int year, int month,
            List<CloudFrontUsageDto> usageData) {
        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow();
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
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new RuntimeException("Cannot edit finalized");
        }
        for (AdminCloudFrontController.LineItemUpdate update : updates) {
            InvoiceLineItem item = invoice.getLineItems().stream().filter(li -> li.getId().equals(update.id))
                    .findFirst().orElseThrow();
            String[] parts = item.getUsageQuantity().split(" ");
            BigDecimal quantity;
            try {
                quantity = new BigDecimal(parts[0]);
            } catch (Exception e) {
                quantity = BigDecimal.ONE;
            }
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
        if (value == null) {
            return "0.00";
        }
        return new DecimalFormat("#,##0.00").format(value);
    }

    private String formatMonth(String billingPeriod) {
        if (billingPeriod == null) {
            return "";
        }
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
        if (amount == null) return "INR Zero Only";
        long totalPaise = amount.setScale(2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)).longValue();
        long rupees = totalPaise / 100;
        int paise = (int) (totalPaise % 100);
        String rupeeWords = numberToWords(rupees);
        if (paise > 0) {
            return "INR " + rupeeWords + " and " + numberToWords(paise) + " Paise Only";
        }
        return "INR " + rupeeWords + " Only";
    }

    private String numberToWords(long n) {
        if (n == 0) return "Zero";
        if (n < 0) return "Minus " + numberToWords(-n);
        String[] ones = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
                "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
                "Seventeen", "Eighteen", "Nineteen"};
        String[] tens = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};
        if (n < 20) return ones[(int) n];
        if (n < 100) return tens[(int) (n / 10)] + (n % 10 != 0 ? " " + ones[(int) (n % 10)] : "");
        if (n < 1_000) return ones[(int) (n / 100)] + " Hundred" + (n % 100 != 0 ? " " + numberToWords(n % 100) : "");
        if (n < 1_00_000) return numberToWords(n / 1000) + " Thousand" + (n % 1000 != 0 ? " " + numberToWords(n % 1000) : "");
        if (n < 1_00_00_000) return numberToWords(n / 1_00_000) + " Lakh" + (n % 1_00_000 != 0 ? " " + numberToWords(n % 1_00_000) : "");
        return numberToWords(n / 1_00_00_000) + " Crore" + (n % 1_00_00_000 != 0 ? " " + numberToWords(n % 1_00_00_000) : "");
    }
}
