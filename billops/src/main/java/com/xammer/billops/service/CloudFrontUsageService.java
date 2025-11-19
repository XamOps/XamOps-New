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
        logger.info("=======================================================");
        logger.info("STARTING CSV PARSING");
        logger.info("File: {}", file.getOriginalFilename());
        logger.info("Size: {} bytes", file.getSize());
        logger.info("=======================================================");
        
        List<CloudFrontUsageDto> parsedUsage = new ArrayList<>();
        
        String productCodeColName = "lineItem/ProductCode";
        String usageTypeColName = "lineItem/UsageType";
        String usageAmountColName = "lineItem/UsageAmount";
        String costColName = "lineItem/UnblendedCost";
        String regionColName = "product/region";
        String usageUnitColName = "pricing/unit";
    
        // Create CSV parser with explicit configuration (NOT in try-with-resources)
        CSVParser parser = new CSVParserBuilder()
            .withSeparator(',')
            .withIgnoreQuotations(false)
            .build();
        
        logger.debug("CSV Parser configured with comma separator");

        try (
            InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
            CSVReader csvReader = new CSVReaderBuilder(reader)
                .withSkipLines(0)
                .withCSVParser(parser)
                .build()
        ) {
            logger.debug("CSV Reader initialized successfully");
            
            String[] headers = csvReader.readNext();
            if (headers == null) {
                logger.error("CSV file is empty - no header row found");
                throw new RuntimeException("CSV file is empty or header row is missing.");
            }
    
            logger.info("CSV has {} columns", headers.length);
            logger.debug("Header columns: {}", String.join(", ", headers));
    
            Map<String, Integer> headerMap = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerMap.put(headers[i].trim(), i);
                logger.trace("Column {}: '{}'", i, headers[i].trim());
            }
    
            // Validation check
            logger.debug("Validating required columns...");
            if (!headerMap.containsKey(productCodeColName)) {
                logger.error("Missing required column: {}", productCodeColName);
            }
            if (!headerMap.containsKey(usageTypeColName)) {
                logger.error("Missing required column: {}", usageTypeColName);
            }
            if (!headerMap.containsKey(usageAmountColName)) {
                logger.error("Missing required column: {}", usageAmountColName);
            }
            if (!headerMap.containsKey(regionColName)) {
                logger.error("Missing required column: {}", regionColName);
            }
            if (!headerMap.containsKey(costColName)) {
                logger.error("Missing required column: {}", costColName);
            }
            
            if (!headerMap.containsKey(productCodeColName) || !headerMap.containsKey(usageTypeColName) ||
                !headerMap.containsKey(usageAmountColName) || !headerMap.containsKey(regionColName) || 
                !headerMap.containsKey(costColName)) {
                logger.error("CSV file is missing one or more required columns. Found headers: {}", headerMap.keySet());
                throw new RuntimeException("Invalid CUR file format. Missing required columns (e.g., " + productCodeColName + ")");
            }
            
            logger.info("All required columns found ✓");
    
            int productCodeIdx = headerMap.get(productCodeColName);
            int usageTypeIdx = headerMap.get(usageTypeColName);
            int usageAmountIdx = headerMap.get(usageAmountColName);
            int regionIdx = headerMap.get(regionColName);
            int costIdx = headerMap.get(costColName);
            Integer unitIdx = headerMap.get(usageUnitColName);
    
            logger.debug("Column indices - ProductCode: {}, UsageType: {}, Amount: {}, Region: {}, Cost: {}, Unit: {}", 
                productCodeIdx, usageTypeIdx, usageAmountIdx, regionIdx, costIdx, unitIdx);
    
            String[] record;
            int rowCount = 0;
            int cloudFrontRowCount = 0;
            int includedRowCount = 0;
            int skippedRowCount = 0;
            
            logger.info("Starting row-by-row parsing...");
            
            while ((record = csvReader.readNext()) != null) {
                rowCount++;
                
                if (rowCount % 10 == 0) {
                    logger.debug("Processed {} rows so far...", rowCount);
                }
                
                String pCode = record[productCodeIdx];
                
                if ("AmazonCloudFront".equalsIgnoreCase(pCode) || "Amazon CloudFront".equalsIgnoreCase(pCode)) {
                    cloudFrontRowCount++;
                    
                    try {
                        String region = record[regionIdx];
                        if (region == null || region.isEmpty() || region.isBlank()) {
                            region = "Global";
                            logger.trace("Row {}: Empty region, defaulting to 'Global'", rowCount);
                        }
                        
                        String usageType = record[usageTypeIdx];
                        double quantity = Double.parseDouble(record[usageAmountIdx]);
                        double cost = Double.parseDouble(record[costIdx]);
                        
                        String unit = "GB";
                        if (unitIdx != null && record.length > unitIdx) {
                            unit = record[unitIdx];
                        } else {
                            if (usageType.contains("Requests")) unit = "Requests";
                            else if (usageType.contains("Bytes")) unit = "GB";
                        }
    
                        // UPDATED: Include rows with cost > 0 OR quantity > 0 (to show free tier usage)
                        if (cost > 0 || quantity > 0) {
                            parsedUsage.add(new CloudFrontUsageDto(region, usageType, quantity, unit, cost));
                            includedRowCount++;
                            logger.debug("✓ Row {}: {} | {} | {} {} | ${}", rowCount, region, usageType, quantity, unit, cost);
                        } else {
                            skippedRowCount++;
                            logger.trace("✗ Row {}: Skipped (cost=0, quantity=0) - {} | {}", rowCount, region, usageType);
                        }
                    } catch (NumberFormatException e) {
                        skippedRowCount++;
                        logger.warn("✗ Row {}: Skipping bad row - Could not parse usage/cost: {}", rowCount, e.getMessage());
                    } catch (ArrayIndexOutOfBoundsException e) {
                        skippedRowCount++;
                        logger.warn("✗ Row {}: Skipping bad row - Column index out of bounds (expected {} columns, got {})", 
                            rowCount, headers.length, record.length);
                    } catch (Exception e) {
                        skippedRowCount++;
                        logger.warn("✗ Row {}: Skipping row due to unexpected error: {}", rowCount, e.getMessage());
                    }
                } else {
                    logger.trace("Row {}: Not CloudFront (ProductCode: {})", rowCount, pCode);
                }
            }
            
            logger.info("=======================================================");
            logger.info("CSV PARSING COMPLETED");
            logger.info("Total rows processed: {}", rowCount);
            logger.info("CloudFront rows found: {}", cloudFrontRowCount);
            logger.info("Rows included in result: {}", includedRowCount);
            logger.info("Rows skipped: {}", skippedRowCount);
            logger.info("Final parsed items: {}", parsedUsage.size());
            logger.info("=======================================================");
    
        } catch (IOException e) {
            logger.error("=======================================================");
            logger.error("FATAL: IOException while reading CSV file");
            logger.error("Error message: {}", e.getMessage());
            logger.error("=======================================================", e);
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage());
        } catch (Exception e) {
            logger.error("=======================================================");
            logger.error("FATAL: Unexpected error during CSV parsing");
            logger.error("Error type: {}", e.getClass().getName());
            logger.error("Error message: {}", e.getMessage());
            logger.error("=======================================================", e);
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
        
        Pattern regionPattern = Pattern.compile("^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
            "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
            "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|Global)$");
        
        Pattern servicePattern = Pattern.compile("^(?:Amazon CloudFront )?([A-Z]{2}-[A-Za-z-]+|Bandwidth|Invalidations?)");
        
        // Pattern to match lines like "693.163 GB USD 5.65"
        Pattern costLinePattern = Pattern.compile("([0-9,]+(?:\\.[0-9]+)?)\\s+(GB|Requests|URL)\\s+USD\\s+([0-9,]+\\.[0-9]{2})");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
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
                    if (cost > 0) {
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
        
        Pattern regionPattern = Pattern.compile("^(Africa \\(Cape Town\\)|Asia Pacific \\((Mumbai|Singapore|Sydney|Tokyo|Hong Kong|Seoul)\\)|" +
            "Canada \\(Central\\)|EU \\(Ireland\\)|Middle East \\(Bahrain\\)|" +
            "South America \\(Sao Paulo\\)|US East \\(N\\. Virginia\\)|US West \\(Oregon\\)|" +
            "Global|HTTP or HTTPS GET Request Additional Charges)");
        
        Pattern costPattern = Pattern.compile("\\$([0-9,]+\\.[0-9]{2})$");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            Matcher regionMatcher = regionPattern.matcher(line);
            if (regionMatcher.find()) {
                currentRegion = regionMatcher.group(1);
                continue;
            }
            
            Matcher costMatcher = costPattern.matcher(line);
            if (costMatcher.find()) {
                double cost = Double.parseDouble(costMatcher.group(1).replace(",", ""));
                
                if (cost > 0.001) {
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
                            Pattern prevQtyPattern = Pattern.compile("([0-9,]+\\.?[0-9]*)\\s+(GB|Requests|URL|Bytes|-)$");
                            Matcher prevQtyMatcher = prevQtyPattern.matcher(prevLine);
                            
                            if (prevQtyMatcher.find()) {
                                quantity = Double.parseDouble(prevQtyMatcher.group(1).replace(",", ""));
                                unit = prevQtyMatcher.group(2).equals("-") ? "Requests" : prevQtyMatcher.group(2);
                                
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
                    
                    if (usageType.isEmpty()) usageType = "CloudFront Service";
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
