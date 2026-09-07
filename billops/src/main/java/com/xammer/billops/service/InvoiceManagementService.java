package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.barcodes.BarcodeQRCode;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
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
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.service.cdn.CloudFrontBill;
import com.xammer.billops.service.cdn.CloudFrontBillLine;
import com.xammer.billops.service.cdn.CloudFrontPricingService;
import com.xammer.cloud.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

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
    private static final String COMPANY_GSTIN = "07AAACX3428N1Z0";
    private static final String COMPANY_STATE = "Delhi";
    private static final String COMPANY_STATE_CODE = "07";
    private static final String COMPANY_CIN = "U72900UP2020OPC135808";
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
    private final CloudFrontPricingService cloudFrontPricingService;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache;
    private AccountAccessHelper accountAccessHelper;

    private static final String INVOICE_LIST_CACHE_KEY = "billops:invoices:list:admin";
    private static final long CACHE_TTL_MINUTES = 60;
    private static final BigDecimal IGST_RATE = new BigDecimal("0.18");

    /**
     * Service name CloudFront line items carry. Used to split them onto their own invoice
     * row and to scope a CloudFront-specific discount, so it has to match exactly what
     * ingestion writes -- see CurIngestionService.normaliseServiceName.
     */
    private static final String CLOUDFRONT_SERVICE = "Amazon CloudFront";

    /**
     * Discount scopes that mean "the whole consumption row" rather than one service.
     * Lowercase for case-insensitive lookup.
     */
    private static final Set<String> CONSUMPTION_DISCOUNT_SCOPES = Set.of(
            "aws consumption charge",
            "google cloud consumption",
            "microsoft azure consumption");

    /**
     * Whether a discount's service name means "the whole consumption row" rather than one
     * named service.
     *
     * The GCP and Azure names used to be recognised only by the PDF, never by
     * {@code recalculateTotals}. There they fell through to the per-service branch, which
     * matches line items by exact service name -- and GCP/Azure line items are named after
     * individual services, so the match found nothing and the discount came out as zero.
     * The rendered invoice showed a concession the stored invoice did not carry.
     */
    private static boolean isConsumptionScope(String serviceName) {
        return serviceName != null && CONSUMPTION_DISCOUNT_SCOPES.contains(serviceName.toLowerCase());
    }

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
            CloudFrontPricingService cloudFrontPricingService,
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
        this.cloudFrontPricingService = cloudFrontPricingService;
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
        return generatePdfFromDto(dto, true);
    }

    public ByteArrayInputStream generatePdfFromDto(InvoiceDto dto, boolean includeTax) {
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
            logger.info("[InvoiceGen] Client GSTIN={}, sellerState={}, isIntraState={}, includeTax={}", clientGstin, sellerStateCode, isIntraState, includeTax);

            // 1. Header & Address Section (Combined Grid)
            addHeaderAndAddressSection(document, dto);

            // 2. Fetch USD→INR exchange rate (live API → stored rate → hardcoded fallback)
            double exchangeRate = fetchUsdToInrRate(dto);

            // 3. Main Item Table (amounts shown in INR)
            BigDecimal taxableValueInr = addMainTable(document, dto, isIntraState, exchangeRate);

            if (includeTax) {
                // 4. Tax Section (computed in INR from the INR taxable value)
                BigDecimal taxAmountInr = taxableValueInr.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
                addTaxSection(document, taxableValueInr, taxAmountInr, isIntraState);

                // 5. Footer
                addFooter(document, taxableValueInr.add(taxAmountInr));
            } else {
                // 5. Footer without tax (detail bill)
                addFooter(document, taxableValueInr);
            }

            // 6. IRN Section (only for Tax Invoices with IRN)
            if (includeTax && dto.isIncludeIrn()) {
                addIrnSection(document, pdf);
            }

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

    // --- Hardcoded IRN constants (replace with real API data when available) ---
    private static final String IRN_VALUE   = "c2116b1c67ae4d5b86d1f9488d34a331d8d1ed085dd2989aacbc55b68af67c14";
    private static final String IRN_ACK_NO  = "142518927183155";
    private static final String IRN_ACK_DATE = "2025-12-01 11:59:00";

    private void addIrnSection(Document document, PdfDocument pdf) {
        Table irnTable = new Table(UnitValue.createPercentArray(new float[]{20, 80})).useAllAvailableWidth();
        irnTable.setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));

        // Left cell: QR code generated from the IRN value
        try {
            BarcodeQRCode qr = new BarcodeQRCode(IRN_VALUE);
            Image qrImage = new Image(qr.createFormXObject(pdf));
            qrImage.setWidth(60).setHeight(60);
            Cell qrCell = new Cell().add(qrImage)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                    .setPadding(5)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
            irnTable.addCell(qrCell);
        } catch (Exception e) {
            logger.warn("[InvoiceGen] QR code generation failed: {}", e.getMessage());
            irnTable.addCell(new Cell().add(new Paragraph("QR").setFontSize(8))
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(5));
        }

        // Right cell: IRN details
        Cell detailCell = new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(8);
        detailCell.add(new Paragraph()
                .add(new com.itextpdf.layout.element.Text("IRN : ").setBold())
                .add(new com.itextpdf.layout.element.Text(IRN_VALUE))
                .setFontSize(7).setMarginBottom(3));
        detailCell.add(new Paragraph()
                .add(new com.itextpdf.layout.element.Text("Ack No. : ").setBold())
                .add(new com.itextpdf.layout.element.Text(IRN_ACK_NO))
                .setFontSize(8).setMarginBottom(3));
        detailCell.add(new Paragraph()
                .add(new com.itextpdf.layout.element.Text("Ack Date : ").setBold())
                .add(new com.itextpdf.layout.element.Text(IRN_ACK_DATE))
                .setFontSize(8).setMarginBottom(5));
        detailCell.add(new Paragraph("e-Invoicing detail(s) generated from the Government's e-Invoicing system.")
                .setFontSize(7).setItalic());
        irnTable.addCell(detailCell);

        document.add(irnTable);
    }

    private void addHeaderAndAddressSection(Document document, InvoiceDto dto) {
        // Title: "Tax Invoice" for IRN invoices, "Performa Invoice" otherwise
        String titleText = dto.isIncludeIrn() ? "Tax Invoice" : "Performa Invoice";
        Paragraph title = new Paragraph(titleText)
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
        if (!dto.isIncludeIrn()) {
            document.add(new Paragraph("\n")); // Spacing (omitted when IRN is included to save height)
        }
    }

    private void addMetaCell(Table table, String label, String value) {
        Cell cell = new Cell().setPadding(2).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f));
        cell.add(new Paragraph(label).setBold().setFontSize(7));
        cell.add(new Paragraph(value).setBold().setFontSize(8));
        table.addCell(cell);
    }

    private BigDecimal addMainTable(Document document, InvoiceDto dto, boolean isIntraState, double exchangeRate) {
        // Columns: SI No, Description, HSN/SAC, Quantity, Rate, Per, Amount
        float[] colWidths = {5, 43, 10, 8, 12, 5, 17};
        Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();
        BigDecimal rate = BigDecimal.valueOf(exchangeRate);
        String rateStr = String.format("%.2f", exchangeRate);

        // Two-line headers for first two columns to match standard invoice format
        String[][] headerLines = {
            {"Sl", "No."},
            {"Description of", "Services"},
            {"HSN/SAC", null},
            {"Quantity", null},
            {"Rate", null},
            {"per", null},
            {"Amount", null}
        };
        for (String[] lines : headerLines) {
            Paragraph hp = new Paragraph()
                    .add(new com.itextpdf.layout.element.Text(lines[0]).setBold().setFontSize(8));
            if (lines[1] != null) {
                hp.add("\n").add(new com.itextpdf.layout.element.Text(lines[1]).setBold().setFontSize(8));
            }
            table.addHeaderCell(new Cell().add(hp)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setBackgroundColor(ColorConstants.LIGHT_GRAY)
                    .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
        }

        List<InvoiceDto.LineItemDto> items = dto.getLineItems() != null ? dto.getLineItems() : Collections.emptyList();
        int slNo = 1;
        BigDecimal totalTaxable = BigDecimal.ZERO;

        Map<String, List<InvoiceDto.LineItemDto>> grouped = items.stream().collect(Collectors.groupingBy(
                item -> CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()) ? "CLOUDFRONT" : "STANDARD"));

        String monthStr = formatMonth(dto.getBillingPeriod());

        // CloudFront's total is needed by BOTH rows before either is written: the
        // CLOUDFRONT row bills it, and an "ALL"-scoped discount has to be split across
        // the two rows because its base spans both.
        BigDecimal cfTotalUsd = grouped.getOrDefault("CLOUDFRONT", Collections.emptyList()).stream()
                .map(i -> Optional.ofNullable(i.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal marketplaceForDiscount = Optional.ofNullable(dto.getMarketplaceCharge()).orElse(BigDecimal.ZERO);
        BigDecimal stdDiscountableTotal = grouped.getOrDefault("STANDARD", Collections.emptyList()).stream()
                .map(i -> Optional.ofNullable(i.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .subtract(marketplaceForDiscount)
                .max(BigDecimal.ZERO);

        // Attributed once, so the rows cannot disagree with each other or with the
        // saved invoice. See attributeDiscounts.
        RowDiscounts rowDiscounts = attributeDiscounts(
                dto.getDiscounts(),
                stdDiscountableTotal,
                cfTotalUsd,
                grouped.getOrDefault("STANDARD", Collections.emptyList()));

        // --- ROW 1: Standard Consumption (AWS, GCP, or Azure) ---
        if (grouped.containsKey("STANDARD")) {
            List<InvoiceDto.LineItemDto> stdItems = grouped.get("STANDARD");
            BigDecimal stdTotalAll = stdItems.stream().map(InvoiceDto.LineItemDto::getCost).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            // Marketplace is part of the consumption total but is shown as its own,
            // non-discounted line. Standard (discountable) consumption = total - marketplace.
            BigDecimal marketplaceUsd = Optional.ofNullable(dto.getMarketplaceCharge()).orElse(BigDecimal.ZERO);
            BigDecimal stdTotal = stdTotalAll.subtract(marketplaceUsd);
            if (stdTotal.compareTo(BigDecimal.ZERO) < 0) {
                stdTotal = BigDecimal.ZERO;
            }

            // Only the discount attributable to THIS row.
            //
            // Previously this row fell back to dto.getDiscountAmount() -- the whole
            // invoice's discount -- whenever no consumption-level discount was present.
            // That was the only route by which a per-service discount reached the PDF, so
            // a CloudFront discount was deducted here while the CloudFront row below
            // showed full price. And when a consumption-level discount WAS present the
            // fallback was skipped entirely, so the CloudFront discount vanished from the
            // PDF altogether and the customer was billed more than the saved invoice said.
            BigDecimal discountPercent = rowDiscounts.consumptionPercent();
            BigDecimal discountAmount = rowDiscounts.standard();
            boolean showDiscountInDesc = rowDiscounts.showConsumptionPercent()
                    && discountAmount.compareTo(BigDecimal.ZERO) > 0;

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
            descPara.add(new com.itextpdf.layout.element.Text("\nFor the Period of " + monthStr).setItalic().setFontSize(8));
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

            // --- Marketplace line (added directly, never discounted) ---
            if (marketplaceUsd.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal mpInr = marketplaceUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                Paragraph mpDesc = new Paragraph();
                mpDesc.add(new com.itextpdf.layout.element.Text("AWS Consumption Charge - Marketplace")
                        .setBold().setFontSize(8));
                mpDesc.add(new com.itextpdf.layout.element.Text("\nAmazon Web Services, Inc. - Marketplace")
                        .setItalic().setFontSize(8));
                mpDesc.add(new com.itextpdf.layout.element.Text("\nFor the Period of " + monthStr)
                        .setItalic().setFontSize(8));
                mpDesc.add(new com.itextpdf.layout.element.Text("\n" + accountLabel).setItalic().setFontSize(8));
                mpDesc.add(new com.itextpdf.layout.element.Text(
                        String.format("\n($%.2f @%s)", marketplaceUsd.doubleValue(), rateStr))
                        .setItalic().setFontSize(8));
                addTableRow(table, slNo++, mpDesc, "998315", mpInr);
                totalTaxable = totalTaxable.add(mpInr);
            }
        }

        // --- ROW 2: CloudFront Consumption ---
        if (grouped.containsKey("CLOUDFRONT")) {
            // CloudFront carries its own discount, either scoped directly to
            // "Amazon CloudFront" or as its share of an "ALL"-scoped one. This row used
            // to bill the gross figure unconditionally, which is what let the discount
            // go missing from the PDF while still being present on the saved invoice.
            BigDecimal cfDiscountUsd = rowDiscounts.cloudFront();
            BigDecimal cfNetUsd = cfTotalUsd.subtract(cfDiscountUsd).max(BigDecimal.ZERO);
            BigDecimal cfTotalInr = cfNetUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            Paragraph cfDescPara = new Paragraph();
            cfDescPara.add(new com.itextpdf.layout.element.Text("AWS CloudFront Consumption").setBold().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text("\nAmazon CloudFront Charges").setItalic().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text("\nFor the Period of " + monthStr).setItalic().setFontSize(8));
            cfDescPara.add(new com.itextpdf.layout.element.Text(
                    String.format("\n($%.2f @%s)", cfNetUsd.doubleValue(), rateStr)).setItalic().setFontSize(8));
            if (cfDiscountUsd.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cfPercent = cfTotalUsd.compareTo(BigDecimal.ZERO) > 0
                        ? cfDiscountUsd.multiply(new BigDecimal(100))
                                .divide(cfTotalUsd, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                cfDescPara.add(new com.itextpdf.layout.element.Text(
                        String.format("\nDiscount: %.2f%% (-$%.2f)", cfPercent.doubleValue(), cfDiscountUsd.doubleValue())
                ).setItalic().setFontSize(8));
            }

            addTableRow(table, slNo++, cfDescPara, "998315", cfTotalInr);
            totalTaxable = totalTaxable.add(cfTotalInr);
        }

        // Filler rows (removed when IRN is included to keep everything on one page)
        int fillerRows = dto.isIncludeIrn() ? 0 : 2;
        for (int i = 0; i < fillerRows; i++) {
            for (int j = 0; j < 7; j++) {
                table.addCell(new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setHeight(15));
            }
        }

        // Tax rows: CGST+SGST for intra-state, IGST for inter-state
        BigDecimal halfTax = totalTaxable.multiply(new BigDecimal("0.09")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal igstAmount;
        if (isIntraState) {
            // CGST@9%
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(new Cell().add(new Paragraph().add(new com.itextpdf.layout.element.Text("CGST@9%").setBold().setItalic()).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("9", TextAlignment.RIGHT));
            table.addCell(createCell("%", TextAlignment.LEFT));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            // SGST@9%
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(new Cell().add(new Paragraph().add(new com.itextpdf.layout.element.Text("SGST@9%").setBold().setItalic()).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("9", TextAlignment.RIGHT));
            table.addCell(createCell("%", TextAlignment.LEFT));
            table.addCell(createCell(formatCurrency(halfTax), TextAlignment.RIGHT));
            igstAmount = halfTax.add(halfTax);
        } else {
            // IGST@18%
            igstAmount = totalTaxable.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(new Cell().add(new Paragraph().add(new com.itextpdf.layout.element.Text("IGST@18%").setBold().setItalic()).setFontSize(8))
                    .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("", TextAlignment.CENTER));
            table.addCell(createCell("18", TextAlignment.RIGHT));
            table.addCell(createCell("%", TextAlignment.LEFT));
            table.addCell(createCell(formatCurrency(igstAmount), TextAlignment.RIGHT));
        }

        // Round Off row
        BigDecimal grossTotal = totalTaxable.add(igstAmount);
        BigDecimal roundedTotal = grossTotal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal roundOff = roundedTotal.subtract(grossTotal);
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("Round Off", TextAlignment.RIGHT));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell(formatCurrency(roundOff.abs()), TextAlignment.RIGHT));

        // Bill Details: — inside table, after Round Off, before Total
        table.addCell(new Cell(1, 7)
                .add(new Paragraph("Bill Details:").setBold().setFontSize(8))
                .setBorder(Border.NO_BORDER)
                .setBorderLeft(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setBorderTop(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3).setPaddingLeft(5));
        table.addCell(new Cell(1, 7)
                .add(new Paragraph()
                        .add(new com.itextpdf.layout.element.Text("New Ref " + dto.getInvoiceNumber() + "    " + formatCurrency(roundedTotal) + " ").setFontSize(8))
                        .add(new com.itextpdf.layout.element.Text("Dr").setFontSize(8).setItalic()))
                .setBorder(Border.NO_BORDER)
                .setBorderLeft(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setBorderRight(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3).setPaddingLeft(15));

        // Total Row — inside table
        table.addCell(new Cell(1, 6).add(new Paragraph("Total").setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));
        table.addCell(new Cell().add(new Paragraph("\u20B9 " + formatCurrency(roundedTotal)).setBold().setFontSize(9))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)));

        // Amount Chargeable (in words) | E. & O.E — inside table after Total
        table.addCell(new Cell(1, 6)
                .add(new Paragraph("Amount Chargeable (in words)").setFontSize(8))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3).setPaddingLeft(5));
        table.addCell(new Cell(1, 1)
                .add(new Paragraph("E. & O.E").setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3));

        // INR words — last row inside table (bold)
        table.addCell(new Cell(1, 7)
                .add(new Paragraph(convertNumberToWords(roundedTotal)).setBold().setFontSize(8))
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3).setPaddingLeft(5));

        document.add(table);
        return totalTaxable;
    }

    private void addTableRow(Table table, int si, String desc, String hsn, BigDecimal amt) {
        table.addCell(createCell(String.valueOf(si), TextAlignment.CENTER));
        table.addCell(createCell(desc, TextAlignment.LEFT));
        table.addCell(createCell(hsn, TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.RIGHT));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(new Cell().add(new Paragraph(formatCurrency(amt)).setBold().setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
    }

    private void addTableRow(Table table, int si, Paragraph descPara, String hsn, BigDecimal amt) {
        table.addCell(createCell(String.valueOf(si), TextAlignment.CENTER));
        table.addCell(new Cell().add(descPara).setTextAlignment(TextAlignment.LEFT)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
        table.addCell(createCell(hsn, TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(createCell("", TextAlignment.RIGHT));
        table.addCell(createCell("", TextAlignment.CENTER));
        table.addCell(new Cell().add(new Paragraph(formatCurrency(amt)).setBold().setFontSize(8))
                .setTextAlignment(TextAlignment.RIGHT).setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f)).setPadding(3));
    }

    private Cell createCell(String text, TextAlignment alignment) {
        return new Cell().add(new Paragraph(text).setFontSize(8))
                .setTextAlignment(alignment)
                .setBorder(new SolidBorder(ColorConstants.BLACK, 0.5f))
                .setPadding(3);
    }

    private void addTaxSection(Document document, BigDecimal taxable, BigDecimal tax, boolean isIntraState) {

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
        sigCell.add(new Paragraph("\n\n"));
        sigCell.add(new Paragraph("Authorised Signatory").setFontSize(8));

        footerTable.addCell(bankCell);
        footerTable.addCell(sigCell);
        document.add(footerTable);

        document.add(new Paragraph("Company's PAN: " + COMPANY_PAN).setFontSize(8).setMarginTop(2));
        document.add(new Paragraph("Declaration").setBold().setUnderline().setFontSize(8));
        document.add(new Paragraph(
                "We declare that this invoice shows the actual price of the goods described and that all particulars are true and correct.")
                .setFontSize(8));
        document.add(new Paragraph("This is a Computer Generated Invoice").setFontSize(8).setItalic()
                .setTextAlignment(TextAlignment.CENTER).setMarginTop(5));
    }

    /**
     * Generates a detailed bill PDF showing service → region → resource breakdown
     * with usage quantity and cost in USD. No tax section.
     */
    public ByteArrayInputStream generateDetailBillPdf(InvoiceDto dto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf, PageSize.A4)) {

            document.setMargins(25, 25, 25, 25);

            DeviceRgb headerBg    = new DeviceRgb(41,  55,  99);   // dark navy
            DeviceRgb serviceBg   = new DeviceRgb(224, 229, 245);  // light indigo
            DeviceRgb regionBg    = new DeviceRgb(243, 245, 250);  // very light grey
            DeviceRgb subtotalBg  = new DeviceRgb(210, 216, 238);  // medium indigo

            // ── Title ──────────────────────────────────────────────────────────
            document.add(new Paragraph("Detailed Bill")
                    .setBold().setFontSize(16).setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(4));

            // ── Account / Period Info ───────────────────────────────────────────
            String accountLabel = dto.getAccountName() != null ? dto.getAccountName() : "";
            String accountId    = dto.getAwsAccountId() != null ? dto.getAwsAccountId()
                                : (dto.getGcpProjectId() != null ? dto.getGcpProjectId()
                                : (dto.getAzureSubscriptionId() != null ? dto.getAzureSubscriptionId() : ""));
            if (!accountId.isEmpty()) accountLabel += " (" + accountId + ")";

            document.add(new Paragraph("Account: " + accountLabel)
                    .setFontSize(9).setMarginBottom(2));
            document.add(new Paragraph("Billing Period: " + (dto.getBillingPeriod() != null ? dto.getBillingPeriod() : ""))
                    .setFontSize(9).setMarginBottom(8));

            // ── Collect non-hidden line items ──────────────────────────────────
            List<InvoiceDto.LineItemDto> items = dto.getLineItems() == null ? Collections.emptyList()
                    : dto.getLineItems().stream()
                        .filter(li -> !li.isHidden())
                        .collect(Collectors.toList());

            // Group: service → region → [items]
            Map<String, Map<String, List<InvoiceDto.LineItemDto>>> grouped = new LinkedHashMap<>();
            for (InvoiceDto.LineItemDto li : items) {
                String svc = li.getServiceName() != null ? li.getServiceName() : "";
                String rgn = li.getRegionName()  != null ? li.getRegionName()  : "";
                grouped.computeIfAbsent(svc, k -> new LinkedHashMap<>())
                       .computeIfAbsent(rgn, k -> new ArrayList<>())
                       .add(li);
            }

            // ── Table ──────────────────────────────────────────────────────────
            float[] cols = {55f, 18f, 12f, 15f};
            Table table = new Table(UnitValue.createPercentArray(cols)).useAllAvailableWidth();

            // Header row
            String[] headers = {"SERVICE / REGION / RESOURCE", "USAGE QUANTITY", "UNIT", "AMOUNT (USD)"};
            for (String h : headers) {
                Cell hc = new Cell()
                        .setBackgroundColor(headerBg)
                        .setBorder(Border.NO_BORDER)
                        .setPadding(5);
                hc.add(new Paragraph(h).setBold().setFontSize(8)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setTextAlignment("AMOUNT (USD)".equals(h) || "UNIT".equals(h) || "USAGE QUANTITY".equals(h)
                                ? TextAlignment.RIGHT : TextAlignment.LEFT));
                table.addHeaderCell(hc);
            }

            BigDecimal grandTotal = BigDecimal.ZERO;

            for (Map.Entry<String, Map<String, List<InvoiceDto.LineItemDto>>> svcEntry : grouped.entrySet()) {
                String svcName = svcEntry.getKey();

                // Service subtotal
                BigDecimal svcTotal = svcEntry.getValue().values().stream()
                        .flatMap(List::stream)
                        .map(li -> li.getCost() != null ? li.getCost() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                grandTotal = grandTotal.add(svcTotal);

                // Service row (spans all 4 cols with subtotal in last col)
                Cell svcCell = new Cell(1, 3)
                        .setBackgroundColor(serviceBg)
                        .setBorder(new SolidBorder(new DeviceRgb(180, 190, 220), 0.3f))
                        .setPaddingLeft(6).setPaddingTop(4).setPaddingBottom(4);
                svcCell.add(new Paragraph(svcName).setBold().setFontSize(8));
                table.addCell(svcCell);

                Cell svcAmt = new Cell()
                        .setBackgroundColor(serviceBg)
                        .setBorder(new SolidBorder(new DeviceRgb(180, 190, 220), 0.3f))
                        .setPaddingRight(6).setPaddingTop(4).setPaddingBottom(4);
                svcAmt.add(new Paragraph(String.format("$%.4f", svcTotal.doubleValue()))
                        .setBold().setFontSize(8).setTextAlignment(TextAlignment.RIGHT));
                table.addCell(svcAmt);

                for (Map.Entry<String, List<InvoiceDto.LineItemDto>> rgnEntry : svcEntry.getValue().entrySet()) {
                    String rgnName = rgnEntry.getKey();
                    List<InvoiceDto.LineItemDto> rgnItems = rgnEntry.getValue();

                    // Region subtotal
                    BigDecimal rgnTotal = rgnItems.stream()
                            .map(li -> li.getCost() != null ? li.getCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Region row
                    Cell rgnCell = new Cell(1, 3)
                            .setBackgroundColor(regionBg)
                            .setBorder(new SolidBorder(new DeviceRgb(210, 215, 230), 0.3f))
                            .setPaddingLeft(14).setPaddingTop(3).setPaddingBottom(3);
                    rgnCell.add(new Paragraph(rgnName).setItalic().setFontSize(8)
                            .setFontColor(new DeviceRgb(60, 80, 130)));
                    table.addCell(rgnCell);

                    Cell rgnAmt = new Cell()
                            .setBackgroundColor(regionBg)
                            .setBorder(new SolidBorder(new DeviceRgb(210, 215, 230), 0.3f))
                            .setPaddingRight(6).setPaddingTop(3).setPaddingBottom(3);
                    rgnAmt.add(new Paragraph(String.format("$%.4f", rgnTotal.doubleValue()))
                            .setItalic().setFontSize(8).setTextAlignment(TextAlignment.RIGHT)
                            .setFontColor(new DeviceRgb(60, 80, 130)));
                    table.addCell(rgnAmt);

                    // Resource rows
                    for (InvoiceDto.LineItemDto li : rgnItems) {
                        String resource = li.getResourceName() != null ? li.getResourceName() : "";
                        String qty      = li.getUsageQuantity() != null ? li.getUsageQuantity() : "";
                        String unit     = li.getUnit() != null ? li.getUnit() : "";
                        double cost     = li.getCost() != null ? li.getCost().doubleValue() : 0.0;

                        Cell resCell = new Cell()
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingLeft(24).setPaddingTop(2).setPaddingBottom(2);
                        resCell.add(new Paragraph(resource).setFontSize(7.5f));
                        table.addCell(resCell);

                        Cell qtyCell = new Cell()
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingRight(4).setPaddingTop(2).setPaddingBottom(2);
                        qtyCell.add(new Paragraph(qty).setFontSize(7.5f).setTextAlignment(TextAlignment.RIGHT));
                        table.addCell(qtyCell);

                        Cell unitCell = new Cell()
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingRight(4).setPaddingTop(2).setPaddingBottom(2);
                        unitCell.add(new Paragraph(unit).setFontSize(7.5f).setTextAlignment(TextAlignment.RIGHT));
                        table.addCell(unitCell);

                        Cell costCell = new Cell()
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingRight(6).setPaddingTop(2).setPaddingBottom(2);
                        costCell.add(new Paragraph(String.format("$%.4f", cost))
                                .setFontSize(7.5f).setTextAlignment(TextAlignment.RIGHT));
                        table.addCell(costCell);
                    }
                }
            }

            // Grand total row
            Cell totalLabelCell = new Cell(1, 3)
                    .setBackgroundColor(subtotalBg)
                    .setBorder(new SolidBorder(new DeviceRgb(150, 160, 200), 0.5f))
                    .setPaddingLeft(6).setPaddingTop(5).setPaddingBottom(5);
            totalLabelCell.add(new Paragraph("TOTAL").setBold().setFontSize(9));
            table.addCell(totalLabelCell);

            Cell totalAmtCell = new Cell()
                    .setBackgroundColor(subtotalBg)
                    .setBorder(new SolidBorder(new DeviceRgb(150, 160, 200), 0.5f))
                    .setPaddingRight(6).setPaddingTop(5).setPaddingBottom(5);
            totalAmtCell.add(new Paragraph(String.format("$%.4f", grandTotal.doubleValue()))
                    .setBold().setFontSize(9).setTextAlignment(TextAlignment.RIGHT));
            table.addCell(totalAmtCell);

            document.add(table);

            document.add(new Paragraph("This is a Computer Generated Detailed Bill")
                    .setFontSize(8).setItalic()
                    .setTextAlignment(TextAlignment.CENTER).setMarginTop(10));

        } catch (Exception e) {
            logger.error("Error generating detail bill PDF", e);
            throw new RuntimeException("Detail bill PDF generation failed", e);
        }
        return new ByteArrayInputStream(out.toByteArray());
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

    /**
     * Discount split across the invoice's two billed rows.
     *
     * @param standard               deducted from the consumption row
     * @param cloudFront             deducted from the CloudFront row
     * @param consumptionPercent     percentage to print on the consumption row
     * @param showConsumptionPercent whether that percentage came from a consumption-level
     *                               scope, and so should be shown rather than applied silently
     */
    record RowDiscounts(BigDecimal standard,
                        BigDecimal cloudFront,
                        BigDecimal consumptionPercent,
                        boolean showConsumptionPercent) {
    }

    /**
     * Splits the invoice's discounts across the consumption row and the CloudFront row.
     *
     * Mirrors the scope rules in {@link #recalculateTotals(Invoice)} deliberately, because
     * that method computes the amount actually charged. The PDF previously derived its own
     * figures and disagreed with it: a CloudFront discount was either applied to the wrong
     * row or dropped, so the rendered invoice and the stored one differed by real money.
     * Attributing once, here, is what keeps the two in step.
     *
     * @param stdTotal discountable consumption -- non-CloudFront line items less Marketplace
     * @param cfTotal  CloudFront line items
     * @param stdItems non-CloudFront line items, for per-service scopes
     */
    static RowDiscounts attributeDiscounts(List<InvoiceDto.DiscountDto> discounts,
                                           BigDecimal stdTotal,
                                           BigDecimal cfTotal,
                                           List<InvoiceDto.LineItemDto> stdItems) {
        BigDecimal std = BigDecimal.ZERO;
        BigDecimal cf = BigDecimal.ZERO;
        BigDecimal consumptionPercent = BigDecimal.ZERO;
        boolean showConsumptionPercent = false;

        if (discounts == null || discounts.isEmpty()) {
            return new RowDiscounts(std, cf, consumptionPercent, false);
        }

        for (InvoiceDto.DiscountDto d : discounts) {
            if (d == null || d.getPercentage() == null) {
                continue;
            }
            String svc = Optional.ofNullable(d.getServiceName()).orElse("");
            BigDecimal pct = d.getPercentage().divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(svc)) {
                // Base spans everything but Marketplace, CloudFront included, so it lands
                // on both rows. Applying it only to the consumption row -- as this used to
                // -- under-discounted the invoice by CloudFront's share.
                std = std.add(stdTotal.multiply(pct));
                cf = cf.add(cfTotal.multiply(pct));
                consumptionPercent = d.getPercentage();
                showConsumptionPercent = true;

            } else if (isConsumptionScope(svc)) {
                std = std.add(stdTotal.multiply(pct));
                consumptionPercent = d.getPercentage();
                showConsumptionPercent = true;

            } else if (CLOUDFRONT_SERVICE.equalsIgnoreCase(svc)) {
                cf = cf.add(cfTotal.multiply(pct));

            } else {
                // Named service: only ever a non-CloudFront one, so it reduces the
                // consumption row. Not surfaced as a percentage, since it applies to one
                // service rather than to the row total shown beside it.
                BigDecimal serviceTotal = stdItems.stream()
                        .filter(i -> svc.equalsIgnoreCase(i.getServiceName()))
                        .map(i -> Optional.ofNullable(i.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                std = std.add(serviceTotal.multiply(pct));
            }
        }

        return new RowDiscounts(
                std.setScale(2, RoundingMode.HALF_UP),
                cf.setScale(2, RoundingMode.HALF_UP),
                consumptionPercent,
                showConsumptionPercent);
    }

    private void recalculateTotalsForDto(InvoiceDto invoiceDto) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal preDiscountTotal = Optional.ofNullable(invoiceDto.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        BigDecimal dtoMarketplace = Optional.ofNullable(invoiceDto.getMarketplaceCharge()).orElse(BigDecimal.ZERO);
        List<InvoiceDto.DiscountDto> discounts = Optional.ofNullable(invoiceDto.getDiscounts())
                .orElse(Collections.emptyList());
        List<InvoiceDto.LineItemDto> lineItems = Optional.ofNullable(invoiceDto.getLineItems())
                .orElse(Collections.emptyList());

        for (InvoiceDto.DiscountDto disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                    .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal allBase = preDiscountTotal.subtract(dtoMarketplace).max(BigDecimal.ZERO);
                totalDiscount = totalDiscount.add(allBase.multiply(percentage));
            } else if (isConsumptionScope(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .subtract(dtoMarketplace).max(BigDecimal.ZERO);
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
    public Invoice generateDraftInvoice(String accountId, int year, int month, boolean includeIrn) {
        logger.info("[InvoiceGen] generateDraftInvoice called: accountId='{}', year={}, month={}, includeIrn={}", accountId, year, month, includeIrn);
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
            //
            // Billing Conductor protection:
            //   For CLOSED months (any month before the current calendar month), we use
            //   forceRefresh=false so the call resolves through our pre-extracted monthly
            //   snapshots (AWS_MONTHLY_TOTAL / AWS_MONTHLY_BREAKDOWN_SVC / AWS_MONTHLY_SVC_REGION
            //   keys in cached_data) instead of hitting the CE API, which returns
            //   Billing-Conductor-modified data after April 1st.
            //   For the CURRENT month, forceRefresh=true stays to always get live CE data.
            boolean isClosedMonth = YearMonth.of(year, month).isBefore(YearMonth.now());

            // ✅ Prefer the exact org-wide monthly snapshot that the FinOps "Cost Management"
            // page uses. AWS_MONTHLY_SVC_REGION / AWS_MONTHLY_BREAKDOWN_SVC are captured
            // org-wide (no LINKED_ACCOUNT filter) and stored in the shared cached_data table,
            // so the invoice matches the cost page and the AWS console exactly for closed
            // months — with NO scale-factor normalization.
            if (isClosedMonth) {
                List<ServiceCostDetailDto> snapshotReport =
                        billingService.getMonthlySnapshotReport(cloudAccount.getAwsAccountId(), year, month);
                if (snapshotReport != null && !snapshotReport.isEmpty()) {
                    detailedReport.addAll(snapshotReport);
                    double snapTotal = snapshotReport.stream().mapToDouble(ServiceCostDetailDto::getCost).sum();
                    logger.info("[InvoiceGen] AWS invoice built from monthly snapshot for {}/{} — {} services, total=${} (no scaling)",
                            year, month, snapshotReport.size(), String.format("%.2f", snapTotal));
                }
            }

            // Fall back to the Cost Explorer path only when no snapshot is available
            // (e.g. current month, or a closed month that was never snapshotted).
            if (detailedReport.isEmpty()) {
            boolean refreshFlag = !isClosedMonth; // false for closed months → uses snapshots
            logger.info("[InvoiceGen] month={}/{} isClosedMonth={} → refreshFlag={}",
                    year, month, isClosedMonth, refreshFlag);

            // Step 1: get correct consolidated total from getCostHistory
            BillingDashboardDto summaryDto = billingService.getBillingData(
                    Collections.singletonList(accountId), year, month, refreshFlag);

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
                    Collections.singletonList(accountId), year, month, refreshFlag);

            if (consolidatedTotal > 0 && detailedData != null && !detailedData.isEmpty()) {
                // Compute detailTotal from the ACTUAL costs that will appear as line items
                // (resource costs, or region cost when resources are empty).
                // Using getTotalCost() is unreliable — it can be account-scoped while
                // the nested resource costs are org-scoped (or vice-versa), causing a
                // scale factor of ~1.0 when the true ratio is far from 1.
                double detailTotal = 0.0;
                for (ServiceCostDetailDto svc : detailedData) {
                    if (svc.getRegionCosts() == null) continue;
                    for (RegionCostDto rgn : svc.getRegionCosts()) {
                        List<ResourceCostDto> rList = rgn.getResources();
                        if (rList == null || rList.isEmpty()) {
                            if (rgn.getCost() > 0) detailTotal += rgn.getCost();
                        } else {
                            for (ResourceCostDto r : rList) {
                                if (r.getCost() > 0) detailTotal += r.getCost();
                            }
                        }
                    }
                }
                // Fallback: if no region/resource data yet, use service-level totalCost
                if (detailTotal == 0) {
                    detailTotal = detailedData.stream().mapToDouble(ServiceCostDetailDto::getTotalCost).sum();
                }
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
            } // end if (detailedReport.isEmpty()) — Cost Explorer fallback
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
        invoice.setIncludeIrn(includeIrn);

        // AWS Marketplace charge (BILLING_ENTITY = "AWS Marketplace"). The consumption
        // total (preDiscountTotal) already includes this amount, so we store it
        // separately to (a) show it as its own "AWS Consumption Charge - Marketplace"
        // line and (b) exclude it from any discount.
        if (!"GCP".equals(cloudAccount.getProvider()) && !"Azure".equals(cloudAccount.getProvider())
                && cloudAccount.getAwsAccountId() != null) {
            double marketplace = billingService.getMarketplaceCharge(cloudAccount.getAwsAccountId(), year, month, false);
            if (marketplace > 0) {
                invoice.setMarketplaceCharge(BigDecimal.valueOf(marketplace).setScale(4, RoundingMode.HALF_UP));
            }
        }

        // CloudFront is resold under a private pricing agreement, so its line items are
        // repriced from usage rather than passed through at AWS cost. Done after the loop
        // above so the generic path stays untouched and the swap is one explicit step.
        repriceCloudFrontAtPrivateRates(lineItems, invoice, cloudAccount, year, month);

        recalculateTotals(invoice);
        logger.info("[InvoiceGen] Draft invoice computed for account='{}' {}/{}: {} line items, total={}, includeIrn={}",
                accountId, year, month, lineItems.size(), invoice.getAmount(), includeIrn);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    /**
     * Replaces the cost-based CloudFront line items with ones priced at the client's
     * private per-unit rates.
     *
     * BillOps resells CloudFront at negotiated rates, so the CDN charge is
     * {@code quantity x ourRate} rather than AWS's cost. The generic line-item builder has
     * no way to know that -- it copies whatever cost the detailed report carries -- so the
     * CloudFront rows it produced are discarded here and rebuilt from
     * {@link CloudFrontPricingService}.
     *
     * Usage comes through {@link CloudFrontUsageService#getUsageFromAws}, which prefers the
     * reconciled CUR export for closed months and falls back to Cost Explorer, so pricing
     * works from whichever source is available.
     *
     * Leaves the invoice exactly as it was on any miss -- non-AWS account, no client, no
     * usage, or nothing priced. Billing CloudFront at zero because a lookup failed would be
     * far worse than billing it at cost.
     */
    private void repriceCloudFrontAtPrivateRates(List<InvoiceLineItem> lineItems, Invoice invoice,
                                                 CloudAccount cloudAccount, int year, int month) {
        if ("GCP".equals(cloudAccount.getProvider()) || "Azure".equals(cloudAccount.getProvider())
                || cloudAccount.getAwsAccountId() == null) {
            return;
        }
        Long clientId = cloudAccount.getClient() == null ? null : cloudAccount.getClient().getId();
        if (clientId == null) {
            return;
        }

        try {
            YearMonth period = YearMonth.of(year, month);
            List<CloudFrontUsageDto> usage =
                    cloudFrontUsageService.getUsageFromAws(cloudAccount.getAwsAccountId(), period);
            if (usage == null || usage.isEmpty()) {
                return;
            }

            List<CurAggregates.SvcUsageEntry> rows = new ArrayList<>(usage.size());
            for (CloudFrontUsageDto u : usage) {
                rows.add(new CurAggregates.SvcUsageEntry(
                        CLOUDFRONT_SERVICE, u.getRegion(), u.getUsageType(),
                        u.getCost(), u.getQuantity(), u.getUnit()));
            }

            CloudFrontBill bill = cloudFrontPricingService.price(rows, clientId);
            if (bill.isEmpty()) {
                return;
            }

            boolean removed = lineItems.removeIf(
                    li -> CLOUDFRONT_SERVICE.equalsIgnoreCase(li.getServiceName()));

            for (CloudFrontBillLine line : bill.getLines()) {
                InvoiceLineItem li = new InvoiceLineItem();
                li.setInvoice(invoice);
                li.setServiceName(CLOUDFRONT_SERVICE);
                li.setRegionName(line.getGeographyName());
                // Shows the working -- geography, quantity and the rate applied -- because
                // a CDN charge that appears as a bare total invites a support ticket asking
                // how it was derived.
                li.setResourceName(line.getDisplayDescription());
                li.setUsageQuantity(line.getQuantity() == null ? "0" : line.getQuantity().toPlainString());
                li.setUnit(line.getUnit());
                li.setCost(line.getAmount().setScale(4, RoundingMode.HALF_UP));
                lineItems.add(li);
            }

            logger.info("[InvoiceGen] CloudFront repriced at private rates for account={} {}/{}: "
                            + "{} lines, billed=${}, awsCost=${}, margin=${} (replaced={})",
                    cloudAccount.getAwsAccountId(), year, month, bill.getLines().size(),
                    bill.getTotalAmount(), bill.getTotalAwsCost(), bill.getMargin(), removed);

            if (bill.hasUnpricedLines()) {
                // Loud on purpose: an invoice carrying traffic from a geography with no
                // configured rate is incomplete, and should be caught by whoever reviews it
                // rather than discovered by the customer.
                logger.warn("[InvoiceGen] CloudFront invoice for account={} {}/{} has {} UNPRICED line(s) "
                                + "passed through at AWS cost -- review before sending: {}",
                        cloudAccount.getAwsAccountId(), year, month, bill.getUnpricedLines().size(),
                        bill.getUnpricedLines().stream()
                                .map(CloudFrontBillLine::getNote)
                                .collect(Collectors.joining("; ")));
            }
        } catch (Exception e) {
            logger.error("[InvoiceGen] CloudFront private-rate pricing failed for account={} {}/{} -- "
                            + "leaving cost-based lines in place: {}",
                    cloudAccount.getAwsAccountId(), year, month, e.getMessage(), e);
        }
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

        // Marketplace is never discounted. It is part of preDiscountTotal, so we
        // subtract it from any discount base.
        BigDecimal marketplace = Optional.ofNullable(invoice.getMarketplaceCharge()).orElse(BigDecimal.ZERO);

        List<Discount> discounts = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList());
        BigDecimal totalDiscount = BigDecimal.ZERO;

        for (Discount disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                    .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal allBase = preDiscountTotal.subtract(marketplace).max(BigDecimal.ZERO);
                totalDiscount = totalDiscount.add(allBase.multiply(percentage));

            } else if (isConsumptionScope(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .subtract(marketplace).max(BigDecimal.ZERO);

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
            return java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + year;
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
