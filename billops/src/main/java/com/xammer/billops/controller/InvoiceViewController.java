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
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com"}, allowCredentials = "true")
public class InvoiceViewController {

    private static final Logger logger = LoggerFactory.getLogger(InvoiceViewController.class);
    private final InvoiceManagementService invoiceManagementService;

    public InvoiceViewController(InvoiceManagementService invoiceManagementService) {
        this.invoiceManagementService = invoiceManagementService;
    }

    /**
     * MODIFIED: Endpoint to fetch a FINALIZED invoice for viewing on the frontend.
     * This corresponds to the fetch call in invoices.html.
     */
    @GetMapping("/view")
    public ResponseEntity<InvoiceDto> viewFinalizedInvoice(
            @RequestParam String accountId,
            @RequestParam int year,
            @RequestParam int month) {
        try {
            Invoice finalizedInvoice = invoiceManagementService.getInvoiceForUser(accountId, year, month);
            if (finalizedInvoice == null) {
                // This is the correct use of 404 - when the item truly isn't found
                return ResponseEntity.notFound().build();
            }
            // The error is likely happening in this fromEntity conversion
            return ResponseEntity.ok(InvoiceDto.fromEntity(finalizedInvoice));
        } catch (Exception e) {
            // This will now log the real error (e.g., NullPointerException)
            logger.error("Error occurred while processing finalized invoice for account {}:", accountId, e);
            // And return the correct 500 Internal Server Error status
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Endpoint to preview the effect of a discount on the invoice data.
     * This is now used for temporary, on-the-fly previews before an invoice is saved.
     */
    @PostMapping("/apply-discount-preview")
    public ResponseEntity<InvoiceDto> applyDiscountPreview(@RequestBody ApplyDiscountPreviewRequest request) {
        InvoiceDto updatedInvoice = invoiceManagementService.applyDiscountToTemporaryInvoice(request.getInvoice(), request.getDiscount());
        return ResponseEntity.ok(updatedInvoice);
    }

    /**
     * Endpoint to generate and download a PDF of an invoice DTO.
     * This can be used for both temporary previews and finalized invoices.
     */
    @PostMapping("/download-preview")
    public ResponseEntity<byte[]> downloadInvoicePreview(@RequestBody InvoiceDto invoiceDto) {
        try {
            ByteArrayInputStream pdfStream = invoiceManagementService.generatePdfFromDto(invoiceDto);
            byte[] contents = pdfStream.readAllBytes();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            String filename = String.format("invoice-%s-%s.pdf", invoiceDto.getAwsAccountId(), invoiceDto.getBillingPeriod());
            headers.setContentDispositionFormData("attachment", filename);
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

            return ResponseEntity.ok().headers(headers).body(contents);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}