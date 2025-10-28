package com.xammer.billops.controller;

import com.xammer.billops.domain.Invoice;
import com.xammer.billops.dto.ApplyDiscountPreviewRequest;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.service.InvoiceManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;

/**
 * Handles all user-facing requests related to viewing and interacting with invoices.
 */
@RestController
@RequestMapping("/api/billops/invoices")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com", "https://live.xamops.com"}, allowCredentials = "true")
public class InvoiceViewController {

    private static final Logger logger = LoggerFactory.getLogger(InvoiceViewController.class);
    private final InvoiceManagementService invoiceManagementService;

    public InvoiceViewController(InvoiceManagementService invoiceManagementService) {
        this.invoiceManagementService = invoiceManagementService;
    }

    /**
     * MODIFIED: Endpoint to fetch a FINALIZED invoice DTO for viewing on the frontend.
     * This corresponds to the fetch call in invoices.html.
     */
    @GetMapping("/view")
    public ResponseEntity<InvoiceDto> viewFinalizedInvoice(
            @RequestParam String accountId,
            @RequestParam int year,
            @RequestParam int month) {
        try {
            // Service method now returns InvoiceDto directly
            InvoiceDto finalizedInvoiceDto = invoiceManagementService.getInvoiceForUser(accountId, year, month);

            if (finalizedInvoiceDto == null) {
                // Return 404 if the DTO is null (meaning the invoice wasn't found)
                return ResponseEntity.notFound().build();
            }
            // Return the DTO directly
            return ResponseEntity.ok(finalizedInvoiceDto);
        } catch (Exception e) {
            // Log the actual error
            logger.error("Error occurred while processing finalized invoice for account {}:", accountId, e);
            // Return 500 Internal Server Error
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Endpoint to preview the effect of a discount on the invoice data.
     * This is now used for temporary, on-the-fly previews before an invoice is saved.
     */
    @PostMapping("/apply-discount-preview")
    public ResponseEntity<InvoiceDto> applyDiscountPreview(@RequestBody ApplyDiscountPreviewRequest request) {
        // Ensure request and its contents are not null
        if (request == null || request.getInvoice() == null || request.getDiscount() == null) {
            logger.warn("Received invalid ApplyDiscountPreviewRequest: {}", request);
            return ResponseEntity.badRequest().build();
        }
        try {
            InvoiceDto updatedInvoice = invoiceManagementService.applyDiscountToTemporaryInvoice(request.getInvoice(), request.getDiscount());
            return ResponseEntity.ok(updatedInvoice);
        } catch (Exception e) {
             logger.error("Error applying discount preview for account {}: {}",
                          (request.getInvoice() != null ? request.getInvoice().getAwsAccountId() : "UNKNOWN"),
                          e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }


    /**
     * Endpoint to generate and download a PDF of an invoice DTO.
     * This can be used for both temporary previews and finalized invoices.
     */
    @PostMapping("/download-preview")
    public ResponseEntity<byte[]> downloadInvoicePreview(@RequestBody InvoiceDto invoiceDto) {
         // Basic validation
        if (invoiceDto == null || invoiceDto.getAwsAccountId() == null || invoiceDto.getBillingPeriod() == null) {
             logger.warn("Received invalid InvoiceDto for PDF download: {}", invoiceDto);
             return ResponseEntity.badRequest().build();
        }
        try {
            ByteArrayInputStream pdfStream = invoiceManagementService.generatePdfFromDto(invoiceDto);
            byte[] contents = pdfStream.readAllBytes();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
             // Sanitize filename parts if necessary
            String filename = String.format("invoice-%s-%s.pdf",
                    invoiceDto.getAwsAccountId().replaceAll("[^a-zA-Z0-9_-]", ""), // Basic sanitization
                    invoiceDto.getBillingPeriod().replaceAll("[^a-zA-Z0-9_-]", "") // Basic sanitization
            );

            headers.setContentDispositionFormData("attachment", filename);
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

            return ResponseEntity.ok().headers(headers).body(contents);
        } catch (Exception e) {
             logger.error("Error generating PDF preview for account {}: {}", invoiceDto.getAwsAccountId(), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}