package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
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
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build()
                )
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

                if (cost > 0) {
                    usageList.add(new CloudFrontUsageDto(region, usageType, quantity, unit, cost));
                    logger.debug("AWS Usage: {} | {} | {} {} | ${}", region, usageType, quantity, unit, cost);
                }
            }
        }
        
        logger.info("Retrieved {} CloudFront usage records from AWS", usageList.size());
        return usageList;
    }

    /**
     * Method 2: Parses usage from an uploaded AWS Cost and Usage Report (CUR) CSV file.
     */
    public List<CloudFrontUsageDto> getUsageFromBill(MultipartFile file) {
        logger.info("Starting CUR CSV file parsing for: {}", file.getOriginalFilename());
        List<CloudFrontUsageDto> parsedUsage = new ArrayList<>();
        
        String productCodeColName = "line_item_product_code";
        String usageTypeColName = "line_item_usage_type";
        String usageAmountColName = "line_item_usage_amount";
        String costColName = "line_item_blended_cost";
        String usageUnitColName = "pricing_unit";
        String regionColName = "product_region";

        try (
            InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
            CSVReader csvReader = new CSVReaderBuilder(reader).withSkipLines(0).build()
        ) {
            String[] headers = csvReader.readNext();
            if (headers == null) {
                throw new RuntimeException("CSV file is empty or header row is missing.");
            }

            logger.debug("CSV has {} columns", headers.length);

            Map<String, Integer> headerMap = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerMap.put(headers[i], i);
            }

            if (!headerMap.containsKey(productCodeColName) || !headerMap.containsKey(usageTypeColName) ||
                !headerMap.containsKey(usageAmountColName) || !headerMap.containsKey(usageUnitColName) ||
                !headerMap.containsKey(regionColName) || !headerMap.containsKey(costColName)) {
                logger.error("CSV file is missing one or more required columns. Required: {}", 
                    List.of(productCodeColName, usageTypeColName, usageAmountColName, usageUnitColName, regionColName, costColName));
                throw new RuntimeException("Invalid CUR file format. Missing required columns.");
            }

            int productCodeIdx = headerMap.get(productCodeColName);
            int usageTypeIdx = headerMap.get(usageTypeColName);
            int usageAmountIdx = headerMap.get(usageAmountColName);
            int unitIdx = headerMap.get(usageUnitColName);
            int regionIdx = headerMap.get(regionColName);
            int costIdx = headerMap.get(costColName);

            String[] record;
            int rowCount = 0;
            while ((record = csvReader.readNext()) != null) {
                rowCount++;
                if ("AmazonCloudFront".equalsIgnoreCase(record[productCodeIdx])) {
                    try {
                        String region = record[regionIdx];
                        if (region == null || region.isEmpty() || region.isBlank()) {
                            region = "Global";
                        }
                        String usageType = record[usageTypeIdx];
                        double quantity = Double.parseDouble(record[usageAmountIdx]);
                        double cost = Double.parseDouble(record[costIdx]);
                        String unit = record[unitIdx];

                        if (cost > 0) {
                             parsedUsage.add(new CloudFrontUsageDto(region, usageType, quantity, unit, cost));
                             logger.debug("CSV Row {}: {} | {} | {} {} | ${}", rowCount, region, usageType, quantity, unit, cost);
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("Skipping bad row {}: Could not parse usage/cost", rowCount);
                    } catch (Exception e) {
                         logger.warn("Skipping row {} due to unexpected error: {}", rowCount, e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to parse uploaded CSV file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process file: " + e.getMessage());
        }

        logger.info("Successfully parsed {} CloudFront line items from CUR file.", parsedUsage.size());
        return parsedUsage;
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
            
            logger.info("=== PDF TEXT EXTRACTION (First 2000 chars) ===");
            logger.info("{}", text.substring(0, Math.min(2000, text.length())));
            logger.info("=== END PDF TEXT ===");
            
            parsedUsage = extractCloudFrontUsageFromPdfText(text);
            
        } catch (IOException e) {
            logger.error("Failed to parse PDF file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse PDF file: " + e.getMessage());
        } finally {
            if (document != null) {
                try {
                    document.close();
                    logger.debug("PDF document closed");
                } catch (IOException e) {
                    logger.warn("Error closing PDF document", e);
                }
            }
            
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                    logger.debug("Deleted temporary PDF file");
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary PDF file: {}", tempFile.getName());
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
        logger.info("Starting usage extraction from PDF text...");
        
        String[] lines = text.split("\\n");
        logger.info("PDF has {} lines to parse", lines.length);
        
        // Detect format by looking for key indicators
        boolean isAwsNativeFormat = text.contains("Charges by service") || text.contains("Usage Quantity");
        boolean isDistributorFormat = text.contains("Amazon Internet Services");
        
        logger.info("Detected format: {}", 
            isAwsNativeFormat ? "AWS Native Bill" : 
            (isDistributorFormat ? "Distributor Invoice" : "Unknown - will try both"));
        
        List<CloudFrontUsageDto> result;
        
        if (isAwsNativeFormat) {
            result = parseAwsNativeFormat(lines);
        } else if (isDistributorFormat) {
            result = parseDistributorFormat(lines);
        } else {
            // Try both parsers
            logger.warn("Unable to detect PDF format. Trying both parsers...");
            result = parseAwsNativeFormat(lines);
            if (result.isEmpty()) {
                result = parseDistributorFormat(lines);
            }
        }
        
        return result;
    }

    /**
     * Parser for AWS native bill format - WITH DETAILED DEBUG LOGGING
     */
    private List<CloudFrontUsageDto> parseAwsNativeFormat(String[] lines) {
        logger.info("Using AWS native format parser...");
        List<CloudFrontUsageDto> usageList = new ArrayList<>();
        
        String currentRegion = "Global";
        String currentService = null;
        int matched = 0;
        
        Pattern regionPattern = Pattern.compile("^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
            "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
            "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|Global)$");
        
        Pattern servicePattern = Pattern.compile("^(?:Amazon CloudFront )?([A-Z]{2}-[A-Za-z-]+|Bandwidth|Invalidations?)");
        
        // Cost line pattern - matches "693.163 GB USD 5.65"
// Pattern that WILL work with "693.163 GB USD 4.85"
Pattern costLinePattern = Pattern.compile("([0-9,]+(?:\\.[0-9]+)?)\\s+(GB|Requests|URL)\\s+USD\\s+([0-9,]+\\.[0-9]{2})");

        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // Check for region
            Matcher regionMatcher = regionPattern.matcher(line);
            if (regionMatcher.find()) {
                currentRegion = regionMatcher.group(1);
                logger.debug("Line {}: Region = {}", i + 1, currentRegion);
                continue;
            }
            
            // Check for service name
            Matcher serviceMatcher = servicePattern.matcher(line);
            if (serviceMatcher.find()) {
                currentService = serviceMatcher.group(1);
                logger.debug("Line {}: Service = {}", i + 1, currentService);
                continue;
            }
            
            // Try to match cost line
            if (currentService != null && line.contains("USD")) {
                logger.debug("Line {}: Checking cost line for service '{}': '{}'", i + 1, currentService, line);
                
                Matcher costMatcher = costLinePattern.matcher(line);
                if (costMatcher.find()) {
                    double cost = Double.parseDouble(costMatcher.group(3).replace(",", ""));
                    
                    if (cost > 0) {
                        double quantity = Double.parseDouble(costMatcher.group(1).replace(",", ""));
                        String unit = costMatcher.group(2);
                        
                        usageList.add(new CloudFrontUsageDto(currentRegion, currentService, quantity, unit, cost));
                        matched++;
                        logger.info("✓ Line {}: AWS NATIVE | Region: {} | Type: {} | {} {} | Cost: ${}", 
                            i + 1, currentRegion, currentService, quantity, unit, cost);
                    } else {
                        logger.debug("Line {}: Cost is $0.00, skipping", i + 1);
                    }
                } else {
                    logger.warn("Line {}: Cost pattern DID NOT MATCH: '{}'", i + 1, line);
                }
            }
        }
        
        logger.info("AWS native parser found {} items", matched);
        return usageList;
    }

    /**
     * Parser for distributor invoice format (Amazon Internet Services)
     */
    private List<CloudFrontUsageDto> parseDistributorFormat(String[] lines) {
        logger.info("Using distributor format parser...");
        List<CloudFrontUsageDto> usageList = new ArrayList<>();
        
        String currentRegion = "Global";
        int matched = 0;
        
        Pattern regionPattern = Pattern.compile("^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
            "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
            "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|" +
            "Global|HTTP or HTTPS GET Request Additional Charges)");
        
        Pattern costPattern = Pattern.compile("\\$([0-9,]+\\.[0-9]{2})$");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // Check for region
            Matcher regionMatcher = regionPattern.matcher(line);
            if (regionMatcher.find()) {
                currentRegion = regionMatcher.group(1);
                logger.debug("Line {}: Region = {}", i + 1, currentRegion);
                continue;
            }
            
            // Look for cost at end of line
            Matcher costMatcher = costPattern.matcher(line);
            if (costMatcher.find()) {
                double cost = Double.parseDouble(costMatcher.group(1).replace(",", ""));
                
                if (cost > 0.001) {
                    String usageType = "";
                    double quantity = 0;
                    String unit = "GB";
                    
                    // Try to extract quantity and unit from current line
                    Pattern qtyPattern = Pattern.compile("([0-9,]+\\.?[0-9]*)\\s+(GB|Requests|URL|Bytes|-)\\s+\\$");
                    Matcher qtyMatcher = qtyPattern.matcher(line);
                    
                    if (qtyMatcher.find()) {
                        quantity = Double.parseDouble(qtyMatcher.group(1).replace(",", ""));
                        unit = qtyMatcher.group(2).equals("-") ? "Requests" : qtyMatcher.group(2);
                        usageType = line.substring(0, qtyMatcher.start()).trim()
                            .replaceAll("\\$\\s*[0-9.]+.*", "").trim();
                    }
                    
                    // Look at previous lines if no data found
                    if (quantity == 0) {
                        for (int j = Math.max(0, i - 3); j < i; j++) {
                            String prevLine = lines[j].trim();
                            Pattern prevQtyPattern = Pattern.compile("([0-9,]+\\.?[0-9]*)\\s+(GB|Requests|URL|Bytes|-)$");
                            Matcher prevQtyMatcher = prevQtyPattern.matcher(prevLine);
                            
                            if (prevQtyMatcher.find()) {
                                quantity = Double.parseDouble(prevQtyMatcher.group(1).replace(",", ""));
                                unit = prevQtyMatcher.group(2).equals("-") ? "Requests" : prevQtyMatcher.group(2);
                                
                                // Look for usage type even further back
                                for (int k = Math.max(0, j - 2); k < j; k++) {
                                    String typeLine = lines[k].trim();
                                    if (!typeLine.contains("$") && !typeLine.matches(".*[0-9,]+.*") && typeLine.length() > 2) {
                                        usageType = typeLine;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                    
                    if (usageType.isEmpty()) {
                        usageType = "CloudFront Service";
                    }
                    
                    usageList.add(new CloudFrontUsageDto(currentRegion, usageType, quantity, unit, cost));
                    matched++;
                    logger.info("✓ Line {}: DISTRIBUTOR | Region: {} | Type: {} | {} {} | Cost: ${}", 
                        i + 1, currentRegion, usageType, quantity, unit, cost);
                }
            }
        }
        
        logger.info("Distributor parser found {} items", matched);
        return usageList;
    }

    /**
     * DTO to hold the normalized usage data.
     */
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

        public String getRegion() { return region; }
        public String getUsageType() { return usageType; }
        public double getQuantity() { return quantity; }
        public String getUnit() { return unit; }
        public double getCost() { return cost; }

        public void setRegion(String region) { this.region = region; }
        public void setUsageType(String usageType) { this.usageType = usageType; }
        public void setQuantity(double quantity) { this.quantity = quantity; }
        public void setUnit(String unit) { this.unit = unit; }
        public void setCost(double cost) { this.cost = cost; }
    }
}
