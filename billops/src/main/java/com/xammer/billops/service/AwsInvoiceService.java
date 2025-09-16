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
import com.opencsv.bean.CsvToBeanBuilder;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AwsInvoiceDto;
import com.xammer.billops.dto.ChargeLineItemDto;
import com.xammer.billops.dto.ChargesByServiceDto;
import com.xammer.billops.dto.CostAndUsageRecord;
import com.xammer.billops.dto.InvoiceSummaryItem;
import com.xammer.billops.repository.CloudAccountRepository;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AwsInvoiceService {

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;


    public AwsInvoiceService(CloudAccountRepository cloudAccountRepository, AwsClientProvider awsClientProvider) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
    }

    public AwsInvoiceDto getInvoiceData(String accountId, int year, int month) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        if (account.getCurS3Bucket() == null || account.getCurReportPath() == null) {
            throw new IllegalStateException("Cost and Usage Report (CUR) S3 bucket and report path are not configured for this account.");
        }

        List<CostAndUsageRecord> records = parseCostAndUsageReport(account, year, month);

        return processRecordsToInvoice(records, account, year, month);
    }

    private List<CostAndUsageRecord> parseCostAndUsageReport(CloudAccount account, int year, int month) {
        S3Client s3Client = awsClientProvider.getS3Client(account);
        String reportKey = String.format("%s/%s%02d01-%s%02d01/%s-Manifest.json",
                account.getCurReportPath(), year, month, year, month + 1, "report-name"); // Adjust report-name

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(account.getCurS3Bucket())
                    .key(reportKey.replace("//", "/")) // Placeholder for actual manifest parsing logic
                    .build();

            ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);

            return new CsvToBeanBuilder<CostAndUsageRecord>(new InputStreamReader(s3Object))
                    .withType(CostAndUsageRecord.class)
                    .build()
                    .parse();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download or parse Cost and Usage Report from S3", e);
        }
    }

    private AwsInvoiceDto processRecordsToInvoice(List<CostAndUsageRecord> records, CloudAccount account, int year, int month) {
        AwsInvoiceDto invoice = new AwsInvoiceDto();
        invoice.setAccountId(account.getAwsAccountId());
        invoice.setAccountName(account.getAccountName());
        invoice.setInvoiceDate(LocalDate.now());
        YearMonth ym = YearMonth.of(year, month);
        invoice.setBillingPeriod(ym.atDay(1).format(DateTimeFormatter.ofPattern("MMM d")) + " - " + ym.atEndOfMonth().format(DateTimeFormatter.ofPattern("MMM d, yyyy")));


        Map<String, List<CostAndUsageRecord>> byService = records.stream()
                .collect(Collectors.groupingBy(CostAndUsageRecord::getProductName));

        List<ChargesByServiceDto> chargesByServiceList = new ArrayList<>();
        byService.forEach((serviceName, serviceRecords) -> {
            ChargesByServiceDto serviceDto = new ChargesByServiceDto();
            serviceDto.setServiceName(serviceName);

            List<ChargeLineItemDto> lineItems = serviceRecords.stream().map(record -> new ChargeLineItemDto(
                    record.getUsageType(),
                    record.getUsageAmount(),
                    String.format("USD %.2f", Double.parseDouble(record.getUnblendedCost())),
                    1
            )).collect(Collectors.toList());

            serviceDto.setLineItems(lineItems);
            double totalServiceCost = serviceRecords.stream().mapToDouble(r -> Double.parseDouble(r.getUnblendedCost())).sum();
            serviceDto.setTotalAmount(totalServiceCost);
            chargesByServiceList.add(serviceDto);
        });

        invoice.setChargesByService(chargesByServiceList);

        double total = chargesByServiceList.stream().mapToDouble(ChargesByServiceDto::getTotalAmount).sum();
        invoice.setEstimatedGrandTotal(total);
        invoice.setTotalPreTax(total);
        invoice.setTotalTax(0.0);
        invoice.setTotalPostTax(total);

        invoice.setHighestServiceSpend(
            chargesByServiceList.stream()
                .max(Comparator.comparing(ChargesByServiceDto::getTotalAmount))
                .map(s -> new InvoiceSummaryItem(s.getServiceName(), s.getTotalAmount(), "N/A"))
                .orElse(null)
        );
        
        // Placeholder for region spend, requires grouping by region as well
        invoice.setHighestRegionSpend(new InvoiceSummaryItem("Multiple Regions", total, "N/A"));


        return invoice;
    }


    public ByteArrayInputStream generateInvoicePdf(AwsInvoiceDto invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            document.add(new Paragraph("aws").setFontColor(ColorConstants.ORANGE).setFontSize(24).setBold());
            document.add(new Paragraph("AWS estimated bill summary").setFontSize(16).setBold());
            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Charges by service").setFontSize(14).setBold());

            Table chargesTable = new Table(UnitValue.createPercentArray(new float[]{6, 2, 2}));
            chargesTable.setWidth(UnitValue.createPercentValue(100));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Description")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Usage Quantity")).setBackgroundColor(ColorConstants.LIGHT_GRAY));
            chargesTable.addHeaderCell(new Cell().add(new Paragraph("Amount in USD")).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.RIGHT));

            for (ChargesByServiceDto serviceCharge : invoice.getChargesByService()) {
                chargesTable.addCell(new Cell(1, 3).add(new Paragraph(serviceCharge.getServiceName()).setBold()));
                for (ChargeLineItemDto lineItem : serviceCharge.getLineItems()) {
                    chargesTable.addCell(new Cell().add(new Paragraph(lineItem.getDescription()).setMarginLeft(lineItem.getIndentationLevel() * 15)));
                    chargesTable.addCell(lineItem.getUsageQuantity());
                    chargesTable.addCell(new Cell().add(new Paragraph(lineItem.getAmount())).setTextAlignment(TextAlignment.RIGHT));
                }
                chargesTable.addCell(new Cell(1, 2)); // Empty cells for alignment
                chargesTable.addCell(new Cell().add(new Paragraph(String.format("USD %.2f", serviceCharge.getTotalAmount()))).setBold().setTextAlignment(TextAlignment.RIGHT));
            }
            document.add(chargesTable);
            document.add(new Paragraph("\n"));

            document.add(new Paragraph("Total pre-tax: $" + String.format("%,.2f", invoice.getTotalPreTax())).setTextAlignment(TextAlignment.RIGHT));
            document.add(new Paragraph("Total tax: $" + String.format("%,.2f", invoice.getTotalTax())).setTextAlignment(TextAlignment.RIGHT));
            document.add(new Paragraph("Total: $" + String.format("%,.2f", invoice.getTotalPostTax())).setBold().setFontSize(14).setTextAlignment(TextAlignment.RIGHT));


        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ByteArrayInputStream(out.toByteArray());
    }
}