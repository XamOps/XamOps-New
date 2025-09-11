package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ExcelExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExportService.class);

    public ByteArrayInputStream generateBillingReport(CloudAccount account, Integer year, Integer month, CostService costService, ResourceService resourceService) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Billing Report for " + account.getAccountName());

            // Header Font Style
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);

            // Header Cell Style
            CellStyle headerCellStyle = workbook.createCellStyle();
            headerCellStyle.setFont(headerFont);

            // Define column headers
            String[] columns = { "Service", "Region", "Resource ID", "Resource Name", "Cost (USD)" };

            // Create Header Row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerCellStyle);
            }

            // Populate Data Rows
            int rowIdx = 1;
            // Use the correct method from the new CostService
            List<Map<String, Object>> services = costService.getCostByDimension(account, "SERVICE", year, month);

            for (Map<String, Object> service : services) {
                String serviceName = (String) service.get("name");
                double serviceCost = (double) service.get("cost");

                Row serviceRow = sheet.createRow(rowIdx++);
                serviceRow.createCell(0).setCellValue(serviceName);
                serviceRow.createCell(4).setCellValue(serviceCost);

                List<Map<String, Object>> regions = costService.getCostForServiceInRegion(account, serviceName, year, month);
                for (Map<String, Object> region : regions) {
                    String regionName = (String) region.get("name");
                    double regionCost = (double) region.get("cost");

                    Row regionRow = sheet.createRow(rowIdx++);
                    regionRow.createCell(1).setCellValue(regionName);
                    regionRow.createCell(4).setCellValue(regionCost);

                    // Assuming getResourcesInRegion returns a compatible structure.
                    // If not, this part might need adjustment based on ResourceService's actual implementation.
                    List<Map<String, Object>> resources = resourceService.getResourcesInRegion(account, regionName, serviceName);
                    for (Map<String, Object> resource : resources) {
                        Row resourceRow = sheet.createRow(rowIdx++);
                        resourceRow.createCell(2).setCellValue((String) resource.get("id"));
                        resourceRow.createCell(3).setCellValue((String) resource.get("name"));
                        resourceRow.createCell(4).setCellValue((double) resource.get("cost"));
                    }
                }
            }

            // Auto-size columns for better readability
            for(int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            logger.info("Successfully created Excel billing report for account {}.", account.getAccountName());
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            logger.error("Failed to export billing data to Excel for account {}", account.getAccountName(), e);
            throw new RuntimeException("Failed to generate Excel file: " + e.getMessage());
        }
    }
}