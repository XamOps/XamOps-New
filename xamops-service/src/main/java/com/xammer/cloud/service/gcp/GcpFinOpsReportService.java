package com.xammer.cloud.service.gcp;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Service
public class GcpFinOpsReportService {

    public ByteArrayInputStream generatePdfReport(GcpFinOpsReportDto reportDto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {

            document.add(new Paragraph("GCP FinOps Report").setBold().setFontSize(20));
            document.add(new Paragraph("Month-to-Date Spend: $" + String.format("%.2f", reportDto.getMonthToDateSpend())));
            document.add(new Paragraph("Forecasted Spend: $" + String.format("%.2f", reportDto.getForecastedSpend())));
            document.add(new Paragraph("Last Month Spend: $" + String.format("%.2f", reportDto.getLastMonthSpend())));
            
            // Add more details from the reportDto to the PDF
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ByteArrayInputStream(out.toByteArray());
    }
}