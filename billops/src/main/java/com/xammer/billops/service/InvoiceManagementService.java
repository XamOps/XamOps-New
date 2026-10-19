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
import com.xammer.billops.service.cur.CurInvoiceDataService;
import com.xammer.billops.service.cur.CurUnavailable;
import com.xammer.billops.service.cur.CurUnavailableException;
import com.xammer.billops.service.pricing.PrivatePricingCalculator;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
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
    private final InvoicePrivatePricingRepository privatePricingRepository;
    private final CloudFrontPricingService cloudFrontPricingService;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache;
    private AccountAccessHelper accountAccessHelper;
    private final CurInvoiceDataService curInvoiceDataService;

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
            InvoicePrivatePricingRepository privatePricingRepository,
            CloudFrontPricingService cloudFrontPricingService,
            ClientRepository clientRepository,
            RedisCacheService redisCache,
            AccountAccessHelper accountAccessHelper,
            CurInvoiceDataService curInvoiceDataService) {
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
        this.privatePricingRepository = privatePricingRepository;
        this.cloudFrontPricingService = cloudFrontPricingService;
        this.clientRepository = clientRepository;
        this.redisCache = redisCache;
        this.accountAccessHelper = accountAccessHelper;
        this.curInvoiceDataService = curInvoiceDataService;
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
     * Priority: 1) Invoice's own finalized rate → 2) CloudAccount stored rate →
     * 3) Live currency API → 4) Hardcoded fallback.
     */
    private double fetchUsdToInrRate(InvoiceDto dto) {
        // 1. Prefer the rate the admin locked in on this invoice at finalize time.
        if (dto.getExchangeRate() != null && dto.getExchangeRate() > 1.0) {
            double invoiceRate = dto.getExchangeRate();
            logger.info("[InvoiceGen] Using invoice-finalized USD→INR rate: {}", invoiceRate);
            return invoiceRate;
        }
        // 2. Try the stored rate on the CloudAccount
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

        // Charges a manual private rate has shielded, per row. Flagged on the line itself
        // by InvoiceDto.fromEntity, so the PDF reads the same decision the saved invoice
        // was computed from rather than re-deriving it from the scope records.
        BigDecimal stdShielded = shieldedTotalOf(grouped.getOrDefault("STANDARD", Collections.emptyList()));
        BigDecimal cfShielded = shieldedTotalOf(grouped.getOrDefault("CLOUDFRONT", Collections.emptyList()));

        // Attributed once, so the rows cannot disagree with each other or with the
        // saved invoice. See attributeDiscounts.
        RowDiscounts rowDiscounts = attributeDiscounts(
                dto.getDiscounts(),
                stdDiscountableTotal,
                cfTotalUsd,
                grouped.getOrDefault("STANDARD", Collections.emptyList()),
                stdShielded,
                cfShielded);

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
                BigDecimal holdingPct = Optional.ofNullable(dto.getMarketplaceHoldingCostPercent())
                        .orElse(BigDecimal.ZERO);
                // Grossed once with RoundingMode.DOWN; the one value feeds both the printed
                // USD sub-line and the INR amount so they cannot drift apart.
                BigDecimal grossedUsd = grossedMarketplace(marketplaceUsd, holdingPct);
                BigDecimal mpInr = grossedUsd.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                Paragraph mpDesc = new Paragraph();
                mpDesc.add(new com.itextpdf.layout.element.Text("AWS Consumption Charge - Marketplace")
                        .setBold().setFontSize(8));
                if (holdingPct.signum() > 0) {
                    // Reference invoice Marketplace cell, verbatim: sub-line "Amazon Web
                    // Service Charges" (NOT "Amazon Web Services, Inc. - Marketplace"), no
                    // Account ID line, and the two literal holding-cost lines.
                    mpDesc.add(new com.itextpdf.layout.element.Text("\nAmazon Web Service Charges")
                            .setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text("\nFor the Period of " + monthStr)
                            .setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text(
                            String.format("\n[$ %.2f + %s%% Holding Cost)",
                                    marketplaceUsd.doubleValue(), trimPercent(holdingPct)))
                            .setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text(
                            String.format("\n($%.2f @%s)", grossedUsd.doubleValue(), rateStr))
                            .setItalic().setFontSize(8));
                } else {
                    // Today's cell, byte-for-byte -- backward compatibility for un-marked-up
                    // invoices.
                    mpDesc.add(new com.itextpdf.layout.element.Text("\nAmazon Web Services, Inc. - Marketplace")
                            .setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text("\nFor the Period of " + monthStr)
                            .setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text("\n" + accountLabel).setItalic().setFontSize(8));
                    mpDesc.add(new com.itextpdf.layout.element.Text(
                            String.format("\n($%.2f @%s)", marketplaceUsd.doubleValue(), rateStr))
                            .setItalic().setFontSize(8));
                }
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
                    rgnCell.add(new Paragraph(regionDisplayName(rgnName)).setItalic().setFontSize(8)
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

                    // Lines built from the Cost and Usage Report carry a usage type and sit
                    // one level deeper, under it, as they do on the AWS bill and the detail
                    // page. Lines without one — Cost Explorer invoices, repriced CloudFront —
                    // stay directly under the region, exactly as before. Usage types keep the
                    // order their first line appears in.
                    Map<String, List<InvoiceDto.LineItemDto>> byUsageType = new LinkedHashMap<>();
                    List<InvoiceDto.LineItemDto> ungrouped = new ArrayList<>();
                    for (InvoiceDto.LineItemDto li : rgnItems) {
                        if (li.getUsageType() == null || li.getUsageType().isBlank()) {
                            ungrouped.add(li);
                        } else {
                            byUsageType.computeIfAbsent(li.getUsageType(), k -> new ArrayList<>()).add(li);
                        }
                    }

                    for (Map.Entry<String, List<InvoiceDto.LineItemDto>> utEntry : byUsageType.entrySet()) {
                        BigDecimal utTotal = utEntry.getValue().stream()
                                .map(li -> li.getCost() != null ? li.getCost() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        Cell utCell = new Cell(1, 3)
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingLeft(22).setPaddingTop(2).setPaddingBottom(2);
                        utCell.add(new Paragraph(utEntry.getKey()).setBold().setFontSize(7.5f));
                        table.addCell(utCell);

                        Cell utAmt = new Cell()
                                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                                .setPaddingRight(6).setPaddingTop(2).setPaddingBottom(2);
                        utAmt.add(new Paragraph(String.format("$%.4f", utTotal.doubleValue()))
                                .setBold().setFontSize(7.5f).setTextAlignment(TextAlignment.RIGHT));
                        table.addCell(utAmt);

                        for (InvoiceDto.LineItemDto li : utEntry.getValue()) {
                            addDetailLineRow(table, li, 32);
                        }
                    }

                    // Resource rows
                    for (InvoiceDto.LineItemDto li : ungrouped) {
                        addDetailLineRow(table, li, 24);
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

            // Says so when any line is billed at a contracted rate rather than the
            // provider's, because those lines no longer match the provider's own invoice
            // and a customer reconciling the two should know why.
            boolean anyPrivatelyPriced = items.stream().anyMatch(InvoiceDto.LineItemDto::isPrivatelyPriced);
            if (anyPrivatelyPriced) {
                document.add(new Paragraph(
                        "Lines marked with a rate (\"@ $x per unit\") are billed at contracted rates "
                                + "rather than the cloud provider's list price.")
                        .setFontSize(7.5f).setItalic().setMarginTop(6));
            }

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
        return attributeDiscounts(discounts, stdTotal, cfTotal, stdItems, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * As above, less the charges that manual private pricing has shielded.
     *
     * <p>The two shielded figures are subtracted from the broad scopes only -- "ALL" and
     * the consumption-level names -- because those are the scopes an admin applies to the
     * invoice as a whole, without naming what is inside it. A discount scoped to one
     * service by name is left alone: the admin picked that service knowing it was priced.
     *
     * @param stdShielded shielded charges inside {@code stdTotal}
     * @param cfShielded  shielded charges inside {@code cfTotal}
     */
    static RowDiscounts attributeDiscounts(List<InvoiceDto.DiscountDto> discounts,
                                           BigDecimal stdTotal,
                                           BigDecimal cfTotal,
                                           List<InvoiceDto.LineItemDto> stdItems,
                                           BigDecimal stdShielded,
                                           BigDecimal cfShielded) {
        BigDecimal std = BigDecimal.ZERO;
        BigDecimal cf = BigDecimal.ZERO;
        BigDecimal consumptionPercent = BigDecimal.ZERO;
        boolean showConsumptionPercent = false;

        if (discounts == null || discounts.isEmpty()) {
            return new RowDiscounts(std, cf, consumptionPercent, false);
        }

        BigDecimal stdDiscountable = stdTotal
                .subtract(Optional.ofNullable(stdShielded).orElse(BigDecimal.ZERO)).max(BigDecimal.ZERO);
        BigDecimal cfDiscountable = cfTotal
                .subtract(Optional.ofNullable(cfShielded).orElse(BigDecimal.ZERO)).max(BigDecimal.ZERO);

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
                std = std.add(stdDiscountable.multiply(pct));
                cf = cf.add(cfDiscountable.multiply(pct));
                consumptionPercent = d.getPercentage();
                showConsumptionPercent = true;

            } else if (isConsumptionScope(svc)) {
                std = std.add(stdDiscountable.multiply(pct));
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

    /** Charges in a set of lines that manual private pricing has shielded from discounts. */
    private static BigDecimal shieldedTotalOf(List<InvoiceDto.LineItemDto> items) {
        return items.stream()
                .filter(InvoiceDto.LineItemDto::isDiscountShielded)
                .map(i -> Optional.ofNullable(i.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void recalculateTotalsForDto(InvoiceDto invoiceDto) {
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal preDiscountTotal = Optional.ofNullable(invoiceDto.getPreDiscountTotal()).orElse(BigDecimal.ZERO);
        BigDecimal dtoMarketplace = Optional.ofNullable(invoiceDto.getMarketplaceCharge()).orElse(BigDecimal.ZERO);
        List<InvoiceDto.DiscountDto> discounts = Optional.ofNullable(invoiceDto.getDiscounts())
                .orElse(Collections.emptyList());
        List<InvoiceDto.LineItemDto> lineItems = Optional.ofNullable(invoiceDto.getLineItems())
                .orElse(Collections.emptyList());

        // Same shield as recalculateTotals, read off the lines rather than recomputed.
        BigDecimal shieldedTotal = lineItems.stream()
                .filter(item -> !item.isHidden())
                .filter(InvoiceDto.LineItemDto::isDiscountShielded)
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shieldedNonCloudFront = lineItems.stream()
                .filter(item -> !item.isHidden())
                .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                .filter(InvoiceDto.LineItemDto::isDiscountShielded)
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (InvoiceDto.DiscountDto disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                    .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal allBase = preDiscountTotal.subtract(dtoMarketplace)
                        .subtract(shieldedTotal).max(BigDecimal.ZERO);
                totalDiscount = totalDiscount.add(allBase.multiply(percentage));
            } else if (isConsumptionScope(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .subtract(dtoMarketplace)
                        .subtract(shieldedNonCloudFront).max(BigDecimal.ZERO);
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
        // Keep this preview path in lockstep with recalculateTotals(Invoice): add the
        // holding-cost markup after the clamp. Inert today because no preview DTO carries a
        // percent, but consistent if one ever does.
        taxableValue = taxableValue.add(holdingCostMargin(
                invoiceDto.getMarketplaceCharge(), invoiceDto.getMarketplaceHoldingCostPercent()));
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

    /**
     * Builds and saves a draft invoice.
     *
     * <p>For an AWS account, {@code source} decides where the figures come from, and the
     * two paths do not mix:
     * <ul>
     *   <li>{@link Invoice.DataSource#CUR} reads the customer's Cost and Usage Report
     *       and nothing else. When the report cannot answer — none connected, the month
     *       predates it, Athena failed — it throws {@link CurUnavailableException}
     *       carrying the reason, and <em>no Cost Explorer call is made</em>. Deciding to
     *       fall back is left to the admin, because the two sources legitimately
     *       disagree and silently switching between them would change a customer's bill
     *       depending on which one happened to answer.</li>
     *   <li>{@link Invoice.DataSource#COST_EXPLORER} is the path this method always had,
     *       unchanged. It is only reached when an admin explicitly chose it.</li>
     * </ul>
     *
     * <p>GCP and Azure accounts ignore {@code source}; they have their own billing
     * exports and never touched Cost Explorer.
     */
    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice generateDraftInvoice(String accountId, int year, int month, boolean includeIrn,
                                        Invoice.DataSource source) {
        logger.info("[InvoiceGen] generateDraftInvoice called: accountId='{}', year={}, month={}, includeIrn={}, source={}",
                accountId, year, month, includeIrn, source);
        // ✅ 1. Find Account by ANY Provider ID (AWS, GCP, or Azure)
        CloudAccount cloudAccount = cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found with ID: " + accountId));

        List<ServiceCostDetailDto> detailedReport = new ArrayList<>();

        boolean isAws = !"GCP".equals(cloudAccount.getProvider()) && !"Azure".equals(cloudAccount.getProvider());
        boolean fromCur = isAws && source == Invoice.DataSource.CUR;
        YearMonth period = YearMonth.of(year, month);

        // What the report said for this month. Only set on the CUR path; the Cost
        // Explorer path keeps its own lookups below.
        List<CurAggregates.ChargeEntry> curCharges = List.of();
        Double curMarketplace = null;

        // ✅ 2. Handle Logic Based on Provider
        if (fromCur) {
            // Everything this branch touches is the report or the stored copy of it.
            // ensureMonthLoaded throws with a reason rather than returning empty, so no
            // code below this line can be reached without CUR data — and nothing in it
            // calls Cost Explorer.
            CurAggregates reportMonth = curInvoiceDataService.ensureMonthLoaded(cloudAccount, period, false);

            curCharges = Optional.ofNullable(reportMonth.getCharges()).orElse(List.of());
            if (curCharges.isEmpty()) {
                throw new CurUnavailableException(CurUnavailable.NO_DATA_FOR_MONTH,
                        "the report holds no billable lines for " + period);
            }
            curMarketplace = reportMonth.getMarketplace();
            if (curMarketplace == null) {
                throw new CurUnavailableException(CurUnavailable.MARKETPLACE_UNMEASURED);
            }

            logger.info("[InvoiceGen] AWS invoice built from the Cost and Usage Report for {} — {} lines, total=${}",
                    period, curCharges.size(),
                    String.format("%.2f", curCharges.stream().mapToDouble(CurAggregates.ChargeEntry::getCost).sum()));
        } else if ("GCP".equals(cloudAccount.getProvider())) {
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
        // The report's own rate lines, built directly rather than through the loop above:
        // that loop drops every line at or below zero, which on this path would bill
        // Savings-Plan-covered usage without the negation that offsets it.
        lineItems.addAll(lineItemsFromCharges(invoice, curCharges));
        invoice.setLineItems(lineItems);
        invoice.setIncludeIrn(includeIrn);
        if (isAws) {
            invoice.setDataSource(fromCur ? Invoice.DataSource.CUR : Invoice.DataSource.COST_EXPLORER);
            // Provisional whichever source built it: Cost Explorer's open month is no
            // more final than the report's, it just does not say so.
            invoice.setProvisional(!period.isBefore(YearMonth.now()));
        }

        // AWS Marketplace charge (BILLING_ENTITY = "AWS Marketplace"). The consumption
        // total (preDiscountTotal) already includes this amount, so we store it
        // separately to (a) show it as its own "AWS Consumption Charge - Marketplace"
        // line and (b) exclude it from any discount.
        if (isAws && cloudAccount.getAwsAccountId() != null) {
            double marketplace = fromCur
                    ? curMarketplace
                    : billingService.getMarketplaceCharge(cloudAccount.getAwsAccountId(), year, month, false);
            if (marketplace > 0) {
                invoice.setMarketplaceCharge(BigDecimal.valueOf(marketplace).setScale(4, RoundingMode.HALF_UP));
            }
        }

        // CloudFront is resold under a private pricing agreement, so its line items are
        // repriced from usage rather than passed through at AWS cost. Done after the loop
        // above so the generic path stays untouched and the swap is one explicit step.
        repriceCloudFrontAtPrivateRates(lineItems, invoice, cloudAccount, year, month, fromCur);

        recalculateTotals(invoice);
        logger.info("[InvoiceGen] Draft invoice computed for account='{}' {}/{}: {} line items, total={}, includeIrn={}",
                accountId, year, month, lineItems.size(), invoice.getAmount(), includeIrn);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        return saved;
    }

    /**
     * A rate written the way a bill should read it: {@code $0.044 per Hrs}.
     *
     * <p>A unit already phrased as "Per 10000 Requests" is not prefixed again, since the
     * admin typed the whole phrase and "per Per 10000 Requests" is what naive
     * concatenation produces. The rate keeps its own precision -- CDN and per-request
     * rates are meaningful several places below a cent, and rounding one to two decimals
     * for display would make the printed arithmetic wrong.
     */
    static String ratePhrase(BigDecimal rate, String rateUnit) {
        if (rate == null) {
            return "";
        }
        String amount = "$" + rate.stripTrailingZeros().toPlainString();
        if (rateUnit == null || rateUnit.isBlank()) {
            return amount + " per unit";
        }
        String unit = rateUnit.trim();
        return unit.toLowerCase().startsWith("per")
                ? amount + " " + unit
                : amount + " per " + unit;
    }

    /** Matches the provider's own rate at the head of a report line's description. */
    private static final java.util.regex.Pattern LEADING_RATE =
            java.util.regex.Pattern.compile("^\\s*\\$\\s*[0-9][0-9,.]*\\s+per\\s+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The text for one line of the detail bill.
     *
     * <p>A charge built from the Cost and Usage Report describes itself with the
     * provider's own rate -- "$0.056 per NAT Gateway Hour". Printing that beside an amount
     * derived from a different rate puts a contradiction on the customer's bill: the rate
     * times the quantity does not equal the total next to it, and the first person to
     * check the arithmetic finds it. So on a repriced line the provider's rate is replaced
     * by the one actually billed, and the description keeps the rest of its wording:
     * "NAT Gateway Hour @ $0.044 per Hrs".
     *
     * <p>Only a leading "$x per " is removed, and only when it is there. A description
     * with no rate in it -- a Cost Explorer resource name, a repriced CloudFront line --
     * is left whole and the applied rate appended.
     */
    static String detailLineDescription(InvoiceDto.LineItemDto li) {
        String text = li.getResourceName() == null ? "" : li.getResourceName();
        if (li.getPrivateUnitRate() == null) {
            return text;
        }
        String subject = LEADING_RATE.matcher(text).replaceFirst("").trim();
        String phrase = ratePhrase(li.getPrivateUnitRate(), billedRateUnit(li));
        return subject.isBlank() ? phrase : subject + " @ " + phrase;
    }

    /**
     * The unit to print a line's private rate against.
     *
     * <p>Normally the unit the admin quoted. When that is missing — a rate applied before
     * the quote was recorded — the line's own unit is correct rather than merely close: a
     * blank rate unit means {@code unitDivisor} returned 1, so the amount was computed as
     * quantity times rate, and the rate is therefore per one unit of whatever the quantity
     * measures. Printing "per unit" there would be vaguer than the arithmetic warrants.
     */
    private static String billedRateUnit(InvoiceDto.LineItemDto li) {
        String quoted = li.getPrivateRateUnit();
        if (quoted != null && !quoted.isBlank()) {
            return quoted;
        }
        String lineUnit = li.getUnit();
        return PrivatePricingCalculator.isPlaceholderUnit(lineUnit) ? null : lineUnit;
    }

    /** One line of the detail bill: text, quantity, unit and amount, indented to its level. */
    private void addDetailLineRow(Table table, InvoiceDto.LineItemDto li, float indent) {
        String resource = detailLineDescription(li);
        String qty      = li.getUsageQuantity() != null ? li.getUsageQuantity() : "";
        String unit     = li.getUnit() != null ? li.getUnit() : "";
        double cost     = li.getCost() != null ? li.getCost().doubleValue() : 0.0;

        Cell resCell = new Cell()
                .setBorder(new SolidBorder(new DeviceRgb(225, 228, 235), 0.3f))
                .setPaddingLeft(indent).setPaddingTop(2).setPaddingBottom(2);
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

    /**
     * "Asia Pacific (Mumbai)" for "ap-south-1", as the AWS bill and the cost page print it.
     *
     * Taken from the SDK's own region metadata rather than a hand-kept map, so a region
     * AWS adds later is named correctly without anyone updating this file. Anything the
     * SDK does not recognise — "Global", or a label a Cost Explorer invoice already
     * carried — is shown unchanged.
     */
    static String regionDisplayName(String region) {
        if (region == null || region.isBlank()) {
            return "";
        }
        try {
            software.amazon.awssdk.regions.RegionMetadata metadata =
                    software.amazon.awssdk.regions.Region.of(region).metadata();
            return metadata != null && metadata.description() != null ? metadata.description() : region;
        } catch (Exception e) {
            return region;
        }
    }

    /** Width of the invoice_line_item text columns, which ddl-auto=update will never widen. */
    private static final int LINE_TEXT_MAX = 255;

    /**
     * One invoice line per rate line of the Cost and Usage Report — the grain of the
     * "Charges by service" section on the cost page, so the invoice and that section list
     * the same lines with the same amounts.
     *
     * <p>Negative lines are kept. On this path they are Savings Plan negations and credits,
     * and the line they offset is on the invoice too; dropping the negative half would
     * bill the covered usage at full on-demand rate.
     *
     * <p>The rate line's description goes in {@code resourceName}, where the detail page
     * and detail bill already show a line's text, and its usage type in {@code usageType},
     * which they group by.
     */
    private List<InvoiceLineItem> lineItemsFromCharges(Invoice invoice, List<CurAggregates.ChargeEntry> charges) {
        List<InvoiceLineItem> lines = new ArrayList<>(charges.size());
        for (CurAggregates.ChargeEntry charge : charges) {
            InvoiceLineItem line = new InvoiceLineItem();
            line.setInvoice(invoice);
            line.setServiceName(clip(charge.getService()));
            line.setRegionName(clip(charge.getRegion()));
            line.setUsageType(clip(charge.getUsageType()));
            line.setResourceName(clip(charge.getDescription()));
            // Plain decimal, no fixed scale: quantities run from 0.000012 GB to 147 million
            // requests, and three decimals would show the first as zero.
            line.setUsageQuantity(BigDecimal.valueOf(charge.getQuantity())
                    .setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            line.setUnit(clip(charge.getUnit()));
            line.setCost(BigDecimal.valueOf(charge.getCost()).setScale(4, RoundingMode.HALF_UP));
            lines.add(line);
        }
        return lines;
    }

    /**
     * Fits text to the column. The longest description on a live report was 107
     * characters, but Marketplace product descriptions are free text, and one over the
     * limit would fail the whole invoice's insert rather than just that line.
     */
    private static String clip(String value) {
        if (value == null || value.length() <= LINE_TEXT_MAX) {
            return value;
        }
        return value.substring(0, LINE_TEXT_MAX - 1) + "…";
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
                                                 CloudAccount cloudAccount, int year, int month,
                                                 boolean fromCur) {
        if ("GCP".equals(cloudAccount.getProvider()) || "Azure".equals(cloudAccount.getProvider())
                || cloudAccount.getAwsAccountId() == null) {
            return;
        }
        Long clientId = cloudAccount.getClient() == null ? null : cloudAccount.getClient().getId();
        if (clientId == null) {
            return;
        }

        YearMonth period = YearMonth.of(year, month);
        List<CloudFrontUsageDto> usage;
        if (fromCur) {
            // Outside the try below on purpose: that block swallows failures and keeps
            // cost-based lines, which is right for a pricing bug but wrong here. Charges
            // with no usage to reprice them from would go out at AWS's own cost instead
            // of the contract rate, so the draft is refused and the admin decides.
            usage = cloudFrontUsageService.getUsageFromCurForInvoice(cloudAccount.getAwsAccountId(), period);
            boolean billsCloudFront = lineItems.stream()
                    .anyMatch(li -> CLOUDFRONT_SERVICE.equalsIgnoreCase(li.getServiceName()));
            if ((usage == null || usage.isEmpty()) && billsCloudFront) {
                throw new CurUnavailableException(CurUnavailable.CLOUDFRONT_USAGE_MISSING);
            }
        } else {
            usage = null; // Fetched inside the try, exactly as before.
        }

        try {
            if (!fromCur) {
                usage = cloudFrontUsageService.getUsageFromAws(cloudAccount.getAwsAccountId(), period);
            }
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

    // ─── Manual private pricing ──────────────────────────────────────────────────
    //
    // Bills part of an invoice at a rate the admin sets instead of at provider cost. The
    // automatic equivalent for CloudFront is repriceCloudFrontAtPrivateRates above, which
    // runs from per-client rates while a draft is generated; this is the manual form,
    // applied to one invoice after the fact.
    //
    // Both work the same way where it matters: the line item's own cost is replaced, so
    // recalculateTotals, the invoice PDF and the detail bill all follow without needing to
    // know a rate was involved.

    /**
     * Prices every charge in a scope at {@code quantity x unitRate}.
     *
     * <p>Refuses rather than guesses, in each of these cases:
     * <ul>
     *   <li><b>Nothing matches the scope.</b> Silently storing a rate that prices no
     *       charge would read, on the invoice, as pricing applied.</li>
     *   <li><b>The scope overlaps a rate already applied.</b> Two rates with a claim on
     *       one line make the result depend on the order they were entered, and removal
     *       ambiguous. The admin removes the broader one first.</li>
     *   <li><b>The scope spans more than one unit.</b> A per-GB rate across CloudFront's
     *       GB and request lines is wrong by orders of magnitude and still looks
     *       plausible. The usage-type level of the scope is the way to price those
     *       separately.</li>
     *   <li><b>No line in the scope has usable usage.</b> Nothing to multiply.</li>
     * </ul>
     *
     * <p>Credits and Savings Plan negations inside the scope are left at provider cost:
     * they carry a positive quantity and a negative amount, so repricing one would turn
     * money owed to the customer into money charged to them. They are counted and
     * reported rather than hidden.
     */
    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice applyPrivatePricing(Long invoiceId, PrivatePricingRequestDto request) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot apply private pricing to a "
                    + String.valueOf(invoice.getStatus()).toLowerCase() + " invoice.");
        }

        String serviceName = blankToNull(request.getServiceName());
        if (serviceName == null) {
            throw new IllegalArgumentException("Choose the service to price.");
        }
        BigDecimal unitRate = request.getUnitRate();
        if (unitRate == null || unitRate.signum() < 0) {
            throw new IllegalArgumentException("Enter a rate of zero or more.");
        }

        InvoicePrivatePricing pricing = new InvoicePrivatePricing();
        pricing.setInvoice(invoice);
        pricing.setServiceName(serviceName);
        pricing.setRegionName(blankToNull(request.getRegionName()));
        pricing.setUsageType(blankToNull(request.getUsageType()));
        pricing.setUnitRate(unitRate);
        pricing.setRateUnit(blankToNull(request.getRateUnit()));
        pricing.setExcludeFromOverallDiscount(request.isExcludeFromOverallDiscount());

        if (invoice.getPrivatePricings() == null) {
            invoice.setPrivatePricings(new ArrayList<>());
        }
        for (InvoicePrivatePricing existing : invoice.getPrivatePricings()) {
            if (existing.overlaps(pricing)) {
                throw new IllegalStateException("These charges are already priced by \""
                        + existing.scopeLabel() + "\". Remove that rate first.");
            }
        }

        List<InvoiceLineItem> inScope = Optional.ofNullable(invoice.getLineItems())
                .orElse(Collections.emptyList()).stream()
                .filter(item -> !item.isHidden())
                .filter(pricing::matches)
                .collect(Collectors.toList());
        if (inScope.isEmpty()) {
            throw new IllegalArgumentException(
                    "No charges on this invoice match " + pricing.scopeLabel() + ".");
        }

        // One rate, one unit. Placeholder units are left out of the check: the region-only
        // fallback line writes "N/A", which names no measure to be inconsistent with.
        Set<String> units = inScope.stream()
                .filter(item -> PrivatePricingCalculator.skipReason(item.getUsageQuantity(), item.getCost()) == null)
                .map(item -> PrivatePricingCalculator.normaliseUnit(item.getUnit()))
                .filter(unit -> !PrivatePricingCalculator.isPlaceholderUnit(unit))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (units.size() > 1) {
            throw new IllegalStateException("These charges are measured in more than one unit ("
                    + String.join(", ", units) + "), so one rate cannot price them all."
                    + " Apply a rate per usage type instead.");
        }

        BigDecimal originalAmount = BigDecimal.ZERO;
        BigDecimal pricedAmount = BigDecimal.ZERO;
        int priced = 0;
        Map<PrivatePricingCalculator.SkipReason, Integer> skipped = new EnumMap<>(PrivatePricingCalculator.SkipReason.class);

        for (InvoiceLineItem item : inScope) {
            PrivatePricingCalculator.SkipReason reason =
                    PrivatePricingCalculator.skipReason(item.getUsageQuantity(), item.getCost());
            if (reason != null) {
                skipped.merge(reason, 1, Integer::sum);
                continue;
            }
            BigDecimal quantity = PrivatePricingCalculator.parseQuantity(item.getUsageQuantity());
            BigDecimal amount = PrivatePricingCalculator.amountFor(quantity, unitRate, pricing.getRateUnit());
            if (amount == null) {
                skipped.merge(PrivatePricingCalculator.SkipReason.UNREADABLE_QUANTITY, 1, Integer::sum);
                continue;
            }

            BigDecimal before = Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO);
            // Only on the first repricing, so re-applying a rate after removing one does
            // not overwrite the provider's figure with our own.
            if (item.getOriginalCost() == null) {
                item.setOriginalCost(before);
            }
            item.setPrivateUnitRate(unitRate);
            item.setPrivateRateUnit(pricing.getRateUnit());
            item.setCost(amount);

            originalAmount = originalAmount.add(Optional.ofNullable(item.getOriginalCost()).orElse(before));
            pricedAmount = pricedAmount.add(amount);
            priced++;
        }

        int skippedCount = skipped.values().stream().mapToInt(Integer::intValue).sum();
        if (priced == 0) {
            // Nothing was mutated above in this case -- every line hit a skip -- so there
            // is nothing to undo, only a reason to give.
            throw new IllegalStateException("None of the charges under " + pricing.scopeLabel()
                    + " carry usage a per-unit rate can price (" + describeSkips(skipped) + ").");
        }

        pricing.setLineCount(priced);
        pricing.setSkippedLineCount(skippedCount);
        pricing.setOriginalAmount(originalAmount.setScale(4, RoundingMode.HALF_UP));
        pricing.setPricedAmount(pricedAmount.setScale(4, RoundingMode.HALF_UP));
        pricing.setAppliedAt(Instant.now());
        pricing.setDescription(describePricing(pricing));
        invoice.getPrivatePricings().add(pricing);

        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        logger.info("[InvoiceMgmt] Private pricing applied to invoice {}: scope='{}', rate={} {}, "
                        + "{} line(s) repriced ({} skipped), providerCost=${} -> billed=${}, shielded={}",
                invoiceId, pricing.scopeLabel(), pricing.getUnitRate(),
                Optional.ofNullable(pricing.getRateUnit()).orElse("per unit"),
                priced, skippedCount, pricing.getOriginalAmount(), pricing.getPricedAmount(),
                pricing.isExcludeFromOverallDiscount());
        return saved;
    }

    /**
     * Turns the overall-discount shield on or off for one applied rate.
     *
     * <p>Changes no charge, only which of them an invoice-wide discount may reduce, so the
     * totals are recomputed and nothing else moves.
     */
    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice setPrivatePricingDiscountExclusion(Long invoiceId, Long pricingId, boolean exclude) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot change private pricing on a "
                    + String.valueOf(invoice.getStatus()).toLowerCase() + " invoice.");
        }
        InvoicePrivatePricing pricing = Optional.ofNullable(invoice.getPrivatePricings())
                .orElse(Collections.emptyList()).stream()
                .filter(p -> Objects.equals(p.getId(), pricingId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Private pricing not found: " + pricingId));

        pricing.setExcludeFromOverallDiscount(exclude);
        pricing.setDescription(describePricing(pricing));

        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        logger.info("[InvoiceMgmt] Private pricing {} on invoice {} is now {} from overall discounts",
                pricingId, invoiceId, exclude ? "shielded" : "NOT shielded");
        return saved;
    }

    /**
     * Sets (or clears) the editable, optional holding-cost markup on a draft invoice's AWS
     * Marketplace charge, then recomputes and persists the totals.
     *
     * <p>Null or zero clears it (identical to a pre-feature invoice). A set value must be
     * within [0, 100]; out of range is an {@link IllegalArgumentException}. DRAFT-only, like
     * private pricing -- the holding cost changes the billed total and must not move after
     * finalize.
     */
    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice setMarketplaceHoldingCost(Long invoiceId, BigDecimal percent) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot change the holding cost on a "
                    + String.valueOf(invoice.getStatus()).toLowerCase() + " invoice.");
        }
        if (percent == null || percent.signum() == 0) {
            invoice.setMarketplaceHoldingCostPercent(null); // clear -> identical to today
        } else {
            if (percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Holding cost must be between 0 and 100 percent.");
            }
            invoice.setMarketplaceHoldingCostPercent(percent.setScale(2, RoundingMode.HALF_UP));
        }
        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        logger.info("[InvoiceMgmt] Marketplace holding cost on invoice {} set to {}",
                invoiceId, invoice.getMarketplaceHoldingCostPercent());
        return saved;
    }

    /**
     * Removes an applied rate and puts its charges back to provider cost.
     *
     * <p>Restoration is exact because the provider's figure was kept on the line itself,
     * and unambiguous because overlapping scopes are refused when applied -- no other rate
     * can have a claim on these lines.
     */
    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice removePrivatePricing(Long invoiceId, Long pricingId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + invoiceId));
        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot change private pricing on a "
                    + String.valueOf(invoice.getStatus()).toLowerCase() + " invoice.");
        }
        Optional<InvoicePrivatePricing> found = Optional.ofNullable(invoice.getPrivatePricings())
                .orElse(Collections.emptyList()).stream()
                .filter(p -> Objects.equals(p.getId(), pricingId))
                .findFirst();
        if (found.isEmpty()) {
            return invoice;
        }
        InvoicePrivatePricing pricing = found.get();

        int restored = 0;
        for (InvoiceLineItem item : Optional.ofNullable(invoice.getLineItems()).orElse(Collections.emptyList())) {
            if (!item.isPrivatelyPriced() || !pricing.matches(item)) {
                continue;
            }
            if (item.getOriginalCost() != null) {
                item.setCost(item.getOriginalCost());
                item.setOriginalCost(null);
            }
            item.setPrivateUnitRate(null);
            item.setPrivateRateUnit(null);
            restored++;
        }

        invoice.getPrivatePricings().remove(pricing);
        privatePricingRepository.delete(pricing);

        recalculateTotals(invoice);
        Invoice saved = invoiceRepository.save(invoice);
        evictInvoiceListCache();
        logger.info("[InvoiceMgmt] Private pricing {} removed from invoice {}: {} line(s) back at provider cost",
                pricingId, invoiceId, restored);
        return saved;
    }

    /** "$0.085 per GB · 12 lines · shielded from overall discount" */
    private static String describePricing(InvoicePrivatePricing pricing) {
        StringBuilder sb = new StringBuilder();
        // Same phrasing the detail bill prints, so the admin page and the customer's bill
        // describe one rate the same way.
        sb.append(ratePhrase(pricing.getUnitRate(), pricing.getRateUnit()));
        if (pricing.getLineCount() != null) {
            sb.append(" · ").append(pricing.getLineCount())
              .append(pricing.getLineCount() == 1 ? " line" : " lines");
        }
        if (pricing.getSkippedLineCount() != null && pricing.getSkippedLineCount() > 0) {
            sb.append(" · ").append(pricing.getSkippedLineCount()).append(" left at provider cost");
        }
        sb.append(pricing.isExcludeFromOverallDiscount()
                ? " · shielded from overall discount"
                : " · overall discount still applies");
        return sb.toString();
    }

    private static String describeSkips(Map<PrivatePricingCalculator.SkipReason, Integer> skipped) {
        if (skipped.isEmpty()) {
            return "no usable usage";
        }
        return skipped.entrySet().stream()
                .map(e -> e.getValue() + " " + switch (e.getKey()) {
                    case CREDIT -> "credit or Savings Plan line";
                    case NO_USAGE -> "line with no measured usage";
                    case UNREADABLE_QUANTITY -> "line with no readable quantity";
                })
                .collect(Collectors.joining(", "));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Whether a charge is priced at an admin's rate that is shielded from the invoice's
     * overall discount.
     *
     * <p>Both halves are needed. The rate marks the line, which keeps credits and
     * unpriceable lines inside a scope discountable -- they are still at provider cost, so
     * there is no negotiated margin to protect. The shield comes from the rate's own
     * checkbox, so an admin can price a service and still let the overall discount reach
     * it.
     */
    private static Predicate<InvoiceLineItem> shieldedFromOverallDiscount(Invoice invoice) {
        List<InvoicePrivatePricing> shields = Optional.ofNullable(invoice.getPrivatePricings())
                .orElse(Collections.emptyList()).stream()
                .filter(InvoicePrivatePricing::isExcludeFromOverallDiscount)
                .collect(Collectors.toList());
        if (shields.isEmpty()) {
            return item -> false;
        }
        return item -> item.isPrivatelyPriced() && shields.stream().anyMatch(p -> p.matches(item));
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

    /**
     * The Marketplace charge after its holding-cost markup, rounded once to scale-2 (the
     * cent that both the printed USD sub-line and the INR amount column derive from, so the
     * two can never disagree). Equals the base when pct is null/<=0.
     *
     * <p>Rounding is {@link RoundingMode#DOWN} (truncation), not HALF_UP: the reference
     * invoice prints {@code 11994.84 x 1.15} as {@code 13794.06} (HALF_UP would give
     * {@code 13794.07}), confirmed end-to-end because {@code 13794.06 x 95.4 = 1,315,953.32}
     * INR matches the reference's amount column. Pinned by a unit test.
     */
    static BigDecimal grossedMarketplace(BigDecimal marketplaceUsd, BigDecimal pct) {
        BigDecimal base = Optional.ofNullable(marketplaceUsd).orElse(BigDecimal.ZERO);
        BigDecimal p = Optional.ofNullable(pct).orElse(BigDecimal.ZERO);
        if (p.signum() <= 0) {
            return base; // null/zero -> no markup
        }
        BigDecimal factor = BigDecimal.ONE.add(p.divide(new BigDecimal(100), 6, RoundingMode.HALF_UP));
        return base.multiply(factor).setScale(2, RoundingMode.DOWN);
    }

    /** The markup portion only (grossed - base); zero when no markup. */
    static BigDecimal holdingCostMargin(BigDecimal marketplaceUsd, BigDecimal pct) {
        return grossedMarketplace(marketplaceUsd, pct)
                .subtract(Optional.ofNullable(marketplaceUsd).orElse(BigDecimal.ZERO));
    }

    /** 15.00 -> "15", 12.50 -> "12.5"; matches the reference's "15%" with no trailing zeros. */
    private static String trimPercent(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString();
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

        // Charges billed at a rate the admin set, where the rate's own checkbox shields
        // them. That rate was negotiated with its margin already in it, so letting an
        // invoice-wide discount reduce it would concede the same margin twice. Subtracted
        // from the broad scopes below, and from those only: a discount aimed at this
        // service by name is the admin saying they mean it.
        Predicate<InvoiceLineItem> shielded = shieldedFromOverallDiscount(invoice);
        BigDecimal shieldedTotal = lineItems.stream()
                .filter(item -> !item.isHidden())
                .filter(shielded)
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shieldedNonCloudFront = lineItems.stream()
                .filter(item -> !item.isHidden())
                .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                .filter(shielded)
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setPrivatePricedTotal(lineItems.stream()
                .filter(item -> !item.isHidden())
                .filter(InvoiceLineItem::isPrivatelyPriced)
                .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP));
        invoice.setDiscountShieldedTotal(shieldedTotal.setScale(4, RoundingMode.HALF_UP));

        List<Discount> discounts = Optional.ofNullable(invoice.getDiscounts()).orElse(Collections.emptyList());
        BigDecimal totalDiscount = BigDecimal.ZERO;

        for (Discount disc : discounts) {
            BigDecimal percentage = Optional.ofNullable(disc.getPercentage()).orElse(BigDecimal.ZERO)
                    .divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);

            if ("ALL".equalsIgnoreCase(disc.getServiceName())) {
                BigDecimal allBase = preDiscountTotal.subtract(marketplace)
                        .subtract(shieldedTotal).max(BigDecimal.ZERO);
                totalDiscount = totalDiscount.add(allBase.multiply(percentage));

            } else if (isConsumptionScope(disc.getServiceName())) {
                BigDecimal standardTotal = lineItems.stream()
                        .filter(item -> !item.isHidden())
                        .filter(item -> !CLOUDFRONT_SERVICE.equalsIgnoreCase(item.getServiceName()))
                        .map(item -> Optional.ofNullable(item.getCost()).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .subtract(marketplace)
                        // CloudFront is already out of this base, so only the rest of the
                        // shielded charges can still be inside it.
                        .subtract(shieldedNonCloudFront).max(BigDecimal.ZERO);

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

        // Holding-cost markup: additive, applied AFTER all discount math and after the
        // clamp, so it can never enter a discount base and a clamped taxable still gets the
        // markup. Discount bases above subtract only the ORIGINAL marketplace USD, so the
        // Marketplace charge and its holding cost are never discounted.
        taxableValue = taxableValue.add(holdingCostMargin(
                invoice.getMarketplaceCharge(), invoice.getMarketplaceHoldingCostPercent()));

        BigDecimal taxAmount = taxableValue.multiply(IGST_RATE).setScale(4, RoundingMode.HALF_UP);
        invoice.setTaxAmount(taxAmount);

        BigDecimal grandTotal = taxableValue.add(taxAmount).setScale(4, RoundingMode.HALF_UP);
        invoice.setAmount(grandTotal);
    }

    public Invoice finalizeInvoice(Long invoiceId) {
        return finalizeInvoice(invoiceId, null);
    }

    @Transactional
    @CacheEvict(value = {"invoices", "invoiceDtos"}, allEntries = true)
    public Invoice finalizeInvoice(Long invoiceId, Double exchangeRate) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        if (exchangeRate != null) {
            if (exchangeRate <= 1.0) {
                throw new IllegalArgumentException("Exchange rate must be greater than 1.");
            }
            invoice.setExchangeRate(exchangeRate);
        }
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
            // Carried through so editing a report-built invoice does not flatten its
            // usage-type level back into a list of bare descriptions.
            newLineItem.setUsageType(itemDto.getUsageType());
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
