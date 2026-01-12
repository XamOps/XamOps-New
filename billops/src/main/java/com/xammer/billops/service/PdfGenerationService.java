package com.xammer.billops.service;

import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.xammer.billops.dto.RcaRequestDto;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PdfGenerationService {

    // Beige/Cream background color
    private static final DeviceRgb BG_COLOR = new DeviceRgb(240, 235, 225);

    public byte[] generateRcaPdf(RcaRequestDto rcaData, String ticketSubject) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);
            
            // --- FIX 1: Add Event Handler to color EVERY page ---
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new BackgroundEventHandler());

            Document document = new Document(pdf, PageSize.A4);
            document.setMargins(30, 40, 30, 40);

            // 1. Add Logo (Top Right)
            addLogo(document);

            // 2. Date Box (Top Left)
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
            Paragraph datePara = new Paragraph(dateStr)
                    .setFontSize(10)
                    .setBold()
                    .setBackgroundColor(ColorConstants.WHITE)
                    .setPadding(3)
                    .setWidth(UnitValue.createPointValue(80))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(-60) // Align with logo
                    .setMarginBottom(40);
            document.add(datePara);

            // 3. Main Title
            document.add(new Paragraph("RCA : [Ticket #" + rcaData.getTicketId() + "]")
                    .setBold().setFontSize(16).setTextAlignment(TextAlignment.CENTER).setMarginBottom(20));

            // --- SECTION 1: Problem Statement ---
            addMainHeader(document, "Problem Statement:");
            document.add(new Paragraph(ticketSubject)
                    .setBold().setFontSize(11).setMarginBottom(5));
            if (rcaData.getProblemStatement() != null) {
                document.add(new Paragraph(rcaData.getProblemStatement())
                        .setFontSize(10).setMarginBottom(15));
            }

            // --- SECTION 2: Incident Overview ---
            addSectionWithBullets(document, "Incident Overview", rcaData.getIncidentOverview());

            // --- SECTION 3: Description of Issue ---
            addSectionWithBullets(document, "Description of the Issue", rcaData.getDescriptionSections());

            // --- SECTION 4: Root Cause Identification ---
            addSectionWithBullets(document, "Root Cause Identification", rcaData.getRootCauseSections());

            // --- SECTION 5: Resolution ---
            addSectionWithBullets(document, "Resolution / Immediate Actions", rcaData.getResolutionSections());

            // --- SECTION 6: Prevention ---
            addSectionWithBullets(document, "Prevention / Future Mitigation", rcaData.getPreventionSections());

            // --- SECTION 7: Conclusion ---
            if (rcaData.getConclusion() != null && !rcaData.getConclusion().isEmpty()) {
                addMainHeader(document, "Conclusion");
                document.add(new Paragraph(rcaData.getConclusion()).setFontSize(10));
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error generating RCA PDF", e);
        }
    }

    // --- FIX 2: Load Logo from File System ---
    private void addLogo(Document document) {
        try {
            byte[] bytes = null;

            // Try loading from local file system (Relative to where the app runs)
            // Path 1: Running from 'billops' folder (sibling to frontend-app)
            File file = new File("../frontend-app/public/icons/xammer-rca-logo.png");
            
            if (!file.exists()) {
                 // Path 2: Running from root 'XamOps-New' folder
                 file = new File("frontend-app/public/icons/xammer-rca-logo.png");
            }
            
            if (file.exists()) {
                bytes = Files.readAllBytes(file.toPath());
            } else {
                // Fallback: Check classpath just in case you decide to move it later
                try {
                     ClassPathResource imgRes = new ClassPathResource("static/icons/xammer-rca-logo.png");
                     if (imgRes.exists()) {
                         try (InputStream is = imgRes.getInputStream()) {
                             bytes = StreamUtils.copyToByteArray(is);
                         }
                     }
                } catch (Exception ignored) {}
            }

            if (bytes != null) {
                ImageData imageData = ImageDataFactory.create(bytes);
                Image logo = new Image(imageData);
                logo.scaleToFit(140, 70); // Adjust size as needed
                logo.setHorizontalAlignment(HorizontalAlignment.RIGHT);
                logo.setMarginBottom(10);
                document.add(logo);
            } else {
                System.err.println("RCA PDF Error: Logo file NOT found at: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("RCA PDF Warning: Could not load logo: " + e.getMessage());
        }
    }

    private void addMainHeader(Document doc, String title) {
        doc.add(new Paragraph("•  " + title)
                .setBold()
                .setFontSize(12)
                .setUnderline()
                .setMarginTop(10)
                .setMarginBottom(5));
    }

    private void addSectionWithBullets(Document doc, String sectionTitle, List<RcaRequestDto.RcaSectionDto> subSections) {
        if (subSections == null || subSections.isEmpty()) return;

        addMainHeader(doc, sectionTitle);

        com.itextpdf.layout.element.List list = new com.itextpdf.layout.element.List()
                .setSymbolIndent(20)
                .setListSymbol("o") 
                .setMarginLeft(20);

        for (RcaRequestDto.RcaSectionDto sub : subSections) {
            ListItem item = new ListItem();
            Paragraph p = new Paragraph();
            if (sub.getHeading() != null && !sub.getHeading().isBlank()) {
                String headingText = sub.getHeading().trim();
                if (!headingText.endsWith(":")) headingText += ":";
                p.add(new Text(headingText + " ").setBold());
            }
            
            if (sub.getContent() != null) {
                p.add(new Text(sub.getContent()));
            }
            
            p.setFontSize(10);
            item.add(p);
            list.add(item);
        }
        doc.add(list);
    }

    // --- Helper Class for Background Color (Fixes multi-page white background) ---
    private static class BackgroundEventHandler implements IEventHandler {
        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            
            // Draw background on the content stream *before* (underneath) existing content
            PdfCanvas canvas = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdfDoc);
            canvas.saveState()
                  .setFillColor(BG_COLOR)
                  .rectangle(0, 0, page.getPageSize().getWidth(), page.getPageSize().getHeight())
                  .fill()
                  .restoreState();
        }
    }
}