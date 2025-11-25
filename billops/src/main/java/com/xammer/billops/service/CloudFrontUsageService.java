package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CloudFrontUsageService {

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private static final Logger logger = LoggerFactory.getLogger(CloudFrontUsageService.class);

    public CloudFrontUsageService(AwsClientProvider awsClientProvider,
            CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Method 1: Fetches CloudFront usage directly from AWS Cost Explorer.
     */
    public List<CloudFrontUsageDto> getUsageFromAws(String accountId, YearMonth period) {
        logger.info("Fetching CloudFront usage from AWS Cost Explorer for account: {}, period: {}", accountId, period);

        CloudAccount cloudAccount = cloudAccountRepository.findByAwsAccountId(accountId)
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + accountId));

        CostExplorerClient client = awsClientProvider.getCostExplorerClient(cloudAccount);

        String start = period.atDay(1).toString();
        String end = period.atEndOfMonth().toString();

        logger.debug("Querying Cost Explorer from {} to {}", start, end);

        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start).end(end).build())
                .granularity(Granularity.MONTHLY)
                .filter(Expression.builder()
                        .dimensions(DimensionValues.builder()
                                .key("SERVICE")
                                .values("Amazon CloudFront")
                                .build())
                        .build())
                .metrics("UsageQuantity", "BlendedCost")
                .groupBy(
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build(),
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build())
                .build();

        GetCostAndUsageResponse response = client.getCostAndUsage(request);
        List<CloudFrontUsageDto> usageList = new ArrayList<>();

        for (ResultByTime result : response.resultsByTime()) {
            for (Group group : result.groups()) {
                String region = group.keys().get(0);
                String usageType = group.keys().get(1);
                double quantity = Double.parseDouble(group.metrics().get("UsageQuantity").amount());
                double cost = Double.parseDouble(group.metrics().get("BlendedCost").amount());
                String unit = group.metrics().get("UsageQuantity").unit();

                if (cost >= 0) {
                    usageList.add(new CloudFrontUsageDto(region, usageType, quantity, unit, cost));
                    logger.debug("AWS Usage: {} | {} | {} {} | ${}", region, usageType, quantity, unit, cost);
                }
            }
        }

        logger.info("Retrieved {} CloudFront usage records from AWS", usageList.size());
        return usageList;
    }

    /**
     * Method 2: Parses usage from an uploaded AWS Cost and Usage Report (CUR) CSV
     * file.
     * UPDATED: Supports both standard AWS keys (lineItem/ProductCode) and
     * snake_case keys (line_item_product_code).
     */
    public List<CloudFrontUsageDto> getUsageFromBill(MultipartFile file) {
        logger.info("=======================================================");
        logger.info("STARTING CSV PARSING");
        logger.info("File: {}", file.getOriginalFilename());

        List<CloudFrontUsageDto> parsedUsage = new ArrayList<>();

        // Define possible column names for each required field to support multiple
        // formats
        String[][] productCodeCandidates = {
                { "lineItem/ProductCode", "line_item_product_code", "product_servicecode" } };
        String[][] usageTypeCandidates = { { "lineItem/UsageType", "line_item_usage_type" } };
        String[][] usageAmountCandidates = { { "lineItem/UsageAmount", "line_item_usage_amount" } };
        String[][] costCandidates = { { "lineItem/UnblendedCost", "line_item_unblended_cost" } };
        String[][] regionCandidates = { { "product/region", "product_region", "product.2.2" } }; // Added product.2.2
                                                                                                 // for your specific
                                                                                                 // file
        String[][] unitCandidates = { { "pricing/unit", "pricing_unit" } };

        // Create CSV parser with explicit configuration
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .withIgnoreQuotations(false)
                .build();

        try (
                InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
                CSVReader csvReader = new CSVReaderBuilder(reader)
                        .withSkipLines(0)
                        .withCSVParser(parser)
                        .build()) {
            String[] headers = csvReader.readNext();
            if (headers == null) {
                throw new RuntimeException("CSV file is empty or header row is missing.");
            }

            // Map header names to their indices
            Map<String, Integer> headerMap = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                // Clean up potential BOM or whitespace
                headerMap.put(headers[i].trim().replaceAll("^\\uFEFF", ""), i);
            }

            // Helper to find index using the candidates arrays
            int productCodeIdx = findColumnIndex(headerMap, productCodeCandidates[0]);
            int usageTypeIdx = findColumnIndex(headerMap, usageTypeCandidates[0]);
            int usageAmountIdx = findColumnIndex(headerMap, usageAmountCandidates[0]);
            int costIdx = findColumnIndex(headerMap, costCandidates[0]);
            int regionIdx = findColumnIndex(headerMap, regionCandidates[0]);
            int unitIdx = findColumnIndex(headerMap, unitCandidates[0]);

            // Check mandatory fields
            if (productCodeIdx == -1 || usageTypeIdx == -1 || usageAmountIdx == -1 || costIdx == -1) {
                logger.error("Missing required columns. Headers found: {}", headerMap.keySet());
                throw new RuntimeException(
                        "Invalid CUR file format. Missing standard columns (e.g., lineItem/ProductCode or line_item_product_code)");
            }

            String[] record;
            int rowCount = 0;

            while ((record = csvReader.readNext()) != null) {
                rowCount++;
                // Safety check for short rows
                if (record.length <= Math.max(productCodeIdx, Math.max(usageTypeIdx, costIdx)))
                    continue;

                String pCode = record[productCodeIdx];

                // Check for CloudFront product code in various formats
                if ("AmazonCloudFront".equalsIgnoreCase(pCode) || "Amazon CloudFront".equalsIgnoreCase(pCode)) {
                    try {
                        String usageType = record[usageTypeIdx];
                        double quantity = 0.0;
                        double cost = 0.0;

                        // Handle potential empty or non-numeric strings safely
                        if (record[usageAmountIdx] != null && !record[usageAmountIdx].isEmpty()) {
                            quantity = Double.parseDouble(record[usageAmountIdx]);
                        }
                        if (record[costIdx] != null && !record[costIdx].isEmpty()) {
                            cost = Double.parseDouble(record[costIdx]);
                        }

                        // Extract Region
                        String region = "Global";
                        if (regionIdx != -1 && record.length > regionIdx) {
                            String rawRegion = record[regionIdx];
                            // Handle the specific format in Xid-90.xlsx:
                            // "product_name:...,region:af-south-1,..."
                            if (rawRegion != null && rawRegion.contains("region:")) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("region:([a-z0-9-]+)")
                                        .matcher(rawRegion);
                                if (m.find()) {
                                    region = m.group(1);
                                }
                            } else if (rawRegion != null && !rawRegion.isBlank()) {
                                region = rawRegion;
                            }
                        }

                        // Determine Unit
                        String unit = "GB";
                        if (unitIdx != -1 && record.length > unitIdx && record[unitIdx] != null
                                && !record[unitIdx].isEmpty()) {
                            unit = record[unitIdx];
                        } else {
                            // Fallback unit detection
                            if (usageType.contains("Requests"))
                                unit = "Requests";
                            else if (usageType.contains("Bytes"))
                                unit = "GB";
                        }

                        // Add if there is usage or cost (changed to >= 0 to catch free tier or zero
                        // cost items)
                        if (cost >= 0 || quantity > 0) {
                            parsedUsage.add(new CloudFrontUsageDto(region, usageType, quantity, unit, cost));
                        }
                    } catch (Exception e) {
                        logger.warn("Skipping row {} due to parsing error: {}", rowCount, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing CSV", e);
            throw new RuntimeException("Failed to process file: " + e.getMessage());
        }

        logger.info("Successfully parsed {} CloudFront line items.", parsedUsage.size());
        return parsedUsage;
    }

    /**
     * Helper method to find the first matching column index from a list of
     * candidates.
     */
    private int findColumnIndex(Map<String, Integer> headerMap, String[] candidates) {
        for (String candidate : candidates) {
            if (headerMap.containsKey(candidate)) {
                return headerMap.get(candidate);
            }
        }
        return -1;
    }

    /**
     * Method 3: Universal parser for both CSV and PDF files
     */
    public List<CloudFrontUsageDto> parseUsageFromFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        logger.info("Parsing file: {}", filename);

        if (filename == null) {
            throw new RuntimeException("Invalid file: no filename");
        }

        if (filename.toLowerCase().endsWith(".csv")) {
            logger.info("File type: CSV");
            return getUsageFromBill(file);
        } else if (filename.toLowerCase().endsWith(".pdf")) {
            logger.info("File type: PDF");
            return parseUsageFromPdf(file);
        } else {
            throw new RuntimeException("Unsupported file type. Only CSV and PDF are supported.");
        }
    }

    /**
     * Parse PDF bill - handles both AWS native and distributor formats
     */
    private List<CloudFrontUsageDto> parseUsageFromPdf(MultipartFile file) {
        logger.info("=================================================");
        logger.info("Starting PDF parsing for: {}", file.getOriginalFilename());
        logger.info("File size: {} bytes", file.getSize());
        logger.info("=================================================");

        List<CloudFrontUsageDto> parsedUsage = new ArrayList<>();
        File tempFile = null;
        PDDocument document = null;

        try {
            tempFile = Files.createTempFile("cloudfront-bill-", ".pdf").toFile();
            file.transferTo(tempFile);
            logger.info("Created temporary file: {}", tempFile.getAbsolutePath());

            document = Loader.loadPDF(tempFile);
            logger.info("PDF loaded successfully. Pages: {}", document.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            logger.info("Text extracted. Total characters: {}", text.length());

            parsedUsage = extractCloudFrontUsageFromPdfText(text);

        } catch (IOException e) {
            logger.error("Failed to parse PDF file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse PDF file: " + e.getMessage());
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    logger.warn("Error closing PDF document", e);
                }
            }

            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary PDF file");
                }
            }
        }

        double totalCost = parsedUsage.stream().mapToDouble(CloudFrontUsageDto::getCost).sum();
        logger.info("=================================================");
        logger.info("PDF parsing complete. Extracted {} line items totaling ${}",
                parsedUsage.size(), String.format("%.2f", totalCost));
        logger.info("=================================================");
        return parsedUsage;
    }

    /**
     * UNIVERSAL PDF PARSER - Auto-detects format and extracts data
     */
    private List<CloudFrontUsageDto> extractCloudFrontUsageFromPdfText(String text) {
        String[] lines = text.split("\\n");

        // Detect format by looking for key indicators
        boolean isAwsNativeFormat = text.contains("Charges by service") || text.contains("Usage Quantity");
        boolean isDistributorFormat = text.contains("Amazon Internet Services");

        if (isAwsNativeFormat) {
            return parseAwsNativeFormat(lines);
        } else if (isDistributorFormat) {
            return parseDistributorFormat(lines);
        } else {
            List<CloudFrontUsageDto> result = parseAwsNativeFormat(lines);
            if (result.isEmpty()) {
                result = parseDistributorFormat(lines);
            }
            return result;
        }
    }

    private List<CloudFrontUsageDto> parseAwsNativeFormat(String[] lines) {
        List<CloudFrontUsageDto> usageList = new ArrayList<>();

        String currentRegion = "Global";
        String currentService = null;

        Pattern regionPattern = Pattern.compile(
                "^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
                        "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
                        "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|Global)$");

        Pattern servicePattern = Pattern
                .compile("^(?:Amazon CloudFront )?([A-Z]{2}-[A-Za-z-]+|Bandwidth|Invalidations?)");

        // Pattern to match lines like "693.163 GB USD 5.65"
        Pattern costLinePattern = Pattern
                .compile("([0-9,]+(?:\\.[0-9]+)?)\\s+(GB|Requests|URL)\\s+USD\\s+([0-9,]+\\.[0-9]{2})");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty())
                continue;

            Matcher regionMatcher = regionPattern.matcher(line);
            if (regionMatcher.find()) {
                currentRegion = regionMatcher.group(1);
                continue;
            }

            Matcher serviceMatcher = servicePattern.matcher(line);
            if (serviceMatcher.find()) {
                currentService = serviceMatcher.group(1);
                continue;
            }

            if (currentService != null && line.contains("USD")) {
                Matcher costMatcher = costLinePattern.matcher(line);
                if (costMatcher.find()) {
                    double cost = Double.parseDouble(costMatcher.group(3).replace(",", ""));
                    // Changed from cost > 0 to cost >= 0 to include zero-cost items if listed
                    if (cost >= 0) {
                        double quantity = Double.parseDouble(costMatcher.group(1).replace(",", ""));
                        String unit = costMatcher.group(2);
                        usageList.add(new CloudFrontUsageDto(currentRegion, currentService, quantity, unit, cost));
                    }
                }
            }
        }
        return usageList;
    }

    private List<CloudFrontUsageDto> parseDistributorFormat(String[] lines) {
        List<CloudFrontUsageDto> usageList = new ArrayList<>();
        String currentRegion = "Global";

        // REMOVED "HTTP or HTTPS GET Request Additional Charges" from here so it is NOT
        // treated as a region.
        // It will now be processed as a usage line item.
        Pattern regionPattern = Pattern.compile(
                "^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
                        "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
                        "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|" +
                        "Global)");

        Pattern costPattern = Pattern.compile("\\$([0-9,]+\\.[0-9]{2})$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty())
                continue;

            Matcher regionMatcher = regionPattern.matcher(line);
            if (regionMatcher.find()) {
                currentRegion = regionMatcher.group(1);
                continue;
            }

            Matcher costMatcher = costPattern.matcher(line);
            if (costMatcher.find()) {
                double cost = Double.parseDouble(costMatcher.group(1).replace(",", ""));

                // Changed from cost > 0.001 to cost >= 0 to catch everything including 0.00
                // items
                if (cost >= 0) {
                    String usageType = "";
                    double quantity = 0;
                    String unit = "GB";

                    Pattern qtyPattern = Pattern.compile("([0-9,]+\\.?[0-9]*)\\s+(GB|Requests|URL|Bytes|-)\\s+\\$");
                    Matcher qtyMatcher = qtyPattern.matcher(line);

                    if (qtyMatcher.find()) {
                        quantity = Double.parseDouble(qtyMatcher.group(1).replace(",", ""));
                        unit = qtyMatcher.group(2).equals("-") ? "Requests" : qtyMatcher.group(2);
                        usageType = line.substring(0, qtyMatcher.start()).trim()
                                .replaceAll("\\$\\s*[0-9.]+.*", "").trim();
                    }

                    if (quantity == 0) {
                        // Fallback: look at previous lines
                        for (int j = Math.max(0, i - 3); j < i; j++) {
                            String prevLine = lines[j].trim();
                            Pattern prevQtyPattern = Pattern
                                    .compile("([0-9,]+\\.?[0-9]*)\\s+(GB|Requests|URL|Bytes|-)$");
                            Matcher prevQtyMatcher = prevQtyPattern.matcher(prevLine);

                            if (prevQtyMatcher.find()) {
                                quantity = Double.parseDouble(prevQtyMatcher.group(1).replace(",", ""));
                                unit = prevQtyMatcher.group(2).equals("-") ? "Requests" : prevQtyMatcher.group(2);

                                for (int k = Math.max(0, j - 2); k < j; k++) {
                                    String typeLine = lines[k].trim();
                                    if (!typeLine.contains("$") && !typeLine.matches(".*[0-9,]+.*")
                                            && typeLine.length() > 2) {
                                        usageType = typeLine;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }

                    if (usageType.isEmpty())
                        usageType = "CloudFront Service";
                    usageList.add(new CloudFrontUsageDto(currentRegion, usageType, quantity, unit, cost));
                }
            }
        }
        return usageList;
    }

    public static class CloudFrontUsageDto {
        private String region;
        private String usageType;
        private double quantity;
        private String unit;
        private double cost;

        public CloudFrontUsageDto(String region, String usageType, double quantity, String unit, double cost) {
            this.region = region;
            this.usageType = usageType;
            this.quantity = quantity;
            this.unit = unit;
            this.cost = cost;
        }

        public String getRegion() {
            return region;
        }

        public String getUsageType() {
            return usageType;
        }

        public double getQuantity() {
            return quantity;
        }

        public String getUnit() {
            return unit;
        }

        public double getCost() {
            return cost;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public void setUsageType(String usageType) {
            this.usageType = usageType;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public void setCost(double cost) {
            this.cost = cost;
        }
    }
}