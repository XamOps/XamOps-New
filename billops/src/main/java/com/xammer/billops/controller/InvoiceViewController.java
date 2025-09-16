package com.xammer.billops.controller;

import com.xammer.billops.domain.Invoice;
import com.xammer.billops.dto.ApplyDiscountPreviewRequest;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.service.InvoiceManagementService;
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
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5500"}, allowCredentials = "true")
public class InvoiceViewController {

    private final InvoiceManagementService invoiceManagementService;

    public InvoiceViewController(InvoiceManagementService invoiceManagementService) {
        this.invoiceManagementService = invoiceManagementService;
    }

    /**
     * Endpoint to generate a temporary invoice for viewing on the frontend.
     * This corresponds to the fetch call in invoices.html.
     */
    @GetMapping("/view")
    public ResponseEntity<InvoiceDto> viewTemporaryInvoice(
            @RequestParam String accountId,
            @RequestParam int year,
            @RequestParam int month) {
        try {
            Invoice tempInvoice = invoiceManagementService.generateTemporaryInvoiceForUser(accountId, year, month);
            if (tempInvoice.getLineItems().isEmpty()) {
                // If no billing data exists, return a 404 to match the frontend's expectation
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(InvoiceDto.fromEntity(tempInvoice));
        } catch (RuntimeException e) {
            // Catches "Cloud account not found" or other errors
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Endpoint to preview the effect of a discount on the invoice data.
     */
    @PostMapping("/apply-discount-preview")
    public ResponseEntity<InvoiceDto> applyDiscountPreview(@RequestBody ApplyDiscountPreviewRequest request) {
        InvoiceDto updatedInvoice = invoiceManagementService.applyDiscountToTemporaryInvoice(request.getInvoice(), request.getDiscount());
        return ResponseEntity.ok(updatedInvoice);
    }

    /**
     * Endpoint to generate and download a PDF of the invoice preview.
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