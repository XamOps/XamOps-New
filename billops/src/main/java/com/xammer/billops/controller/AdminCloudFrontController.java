package com.xammer.billops.controller;

import com.xammer.billops.domain.CloudFrontPrivateRate;
import com.xammer.cloud.domain.Invoice;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import com.xammer.billops.service.CloudFrontUsageService;
import com.xammer.billops.service.CloudFrontUsageService.CloudFrontUsageDto;
import com.xammer.billops.service.InvoiceManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/admin/cloudfront")
//@PreAuthorize("hasRole('ROLE_BILLOPS_ADMIN')")
public class AdminCloudFrontController {

    private final CloudFrontUsageService cloudFrontUsageService;
    private final InvoiceManagementService invoiceManagementService;
    private final CloudFrontPrivateRateRepository privateRateRepository;

    public AdminCloudFrontController(CloudFrontUsageService cloudFrontUsageService,
                                     InvoiceManagementService invoiceManagementService,
                                     CloudFrontPrivateRateRepository privateRateRepository) {
        this.cloudFrontUsageService = cloudFrontUsageService;
        this.invoiceManagementService = invoiceManagementService;
        this.privateRateRepository = privateRateRepository;
    }

    /**
     * Generate invoice from AWS Cost Explorer API
     */
    @PostMapping("/generate-from-aws")
    public ResponseEntity<InvoiceDto> generateFromAws(@RequestBody GenerateRequest req) {
        List<CloudFrontUsageDto> usage =
                cloudFrontUsageService.getUsageFromAws(req.accountId, YearMonth.of(req.year, req.month));

        Invoice invoice = invoiceManagementService.generateCloudFrontInvoice(
                req.accountId, req.year, req.month, usage);

        return ResponseEntity.ok(InvoiceDto.fromEntity(invoice));
    }

    /**
     * DEPRECATED: Use generate-from-file instead
     */
    @PostMapping("/generate-from-upload")
    public ResponseEntity<InvoiceDto> generateFromUpload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("accountId") String accountId,
                                                      @RequestParam("year") int year,
                                                      @RequestParam("month") int month) {
        List<CloudFrontUsageDto> usage =
                cloudFrontUsageService.getUsageFromBill(file);

        Invoice invoice = invoiceManagementService.generateCloudFrontInvoice(
                accountId, year, month, usage);

        return ResponseEntity.ok(InvoiceDto.fromEntity(invoice));
    }

    /**
     * NEW: Unified endpoint that accepts both CSV and PDF files
     * THIS IS THE ENDPOINT THAT WAS MISSING
     */
    @PostMapping("/generate-from-file")
    public ResponseEntity<InvoiceDto> generateFromFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountId") String accountId,
            @RequestParam("year") int year,
            @RequestParam("month") int month) {
        
        // This method automatically detects CSV or PDF and parses accordingly
        List<CloudFrontUsageDto> usage = cloudFrontUsageService.parseUsageFromFile(file);
        
        Invoice invoice = invoiceManagementService.generateCloudFrontInvoice(
                accountId, year, month, usage);
        
        return ResponseEntity.ok(InvoiceDto.fromEntity(invoice));
    }

    /**
     * Get all private rates
     */
    @GetMapping("/rates")
    public ResponseEntity<List<CloudFrontPrivateRate>> getRates() {
        return ResponseEntity.ok(privateRateRepository.findAll());
    }

    /**
     * Get rates for a specific client
     */
    @GetMapping("/rates/client/{clientId}")
    public ResponseEntity<List<CloudFrontPrivateRate>> getRatesByClient(@PathVariable Long clientId) {
        return ResponseEntity.ok(privateRateRepository.findByClientId(clientId));
    }

    /**
     * Create or update a private rate
     */
    @PostMapping("/rates")
    public ResponseEntity<CloudFrontPrivateRate> saveRate(@RequestBody CloudFrontPrivateRate rate) {
        CloudFrontPrivateRate savedRate = privateRateRepository.save(rate);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedRate);
    }
    
    /**
     * Delete a private rate
     */
    @DeleteMapping("/rates/{id}")
    public ResponseEntity<Void> deleteRate(@PathVariable Long id) {
        if (!privateRateRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        privateRateRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update invoice line items (edit rates)
     */
    @PutMapping("/invoice/update")
    public ResponseEntity<InvoiceDto> updateInvoice(@RequestBody UpdateInvoiceRequest request) {
        Invoice updated = invoiceManagementService.updateInvoiceLineItems(
            request.invoiceId, 
            request.lineItems
        );
        return ResponseEntity.ok(InvoiceDto.fromEntity(updated));
    }

    // Request DTOs
    static class GenerateRequest {
        public String accountId;
        public int year;
        public int month;
    }

    static class UpdateInvoiceRequest {
        public Long invoiceId;
        public List<LineItemUpdate> lineItems;
    }

    public static class LineItemUpdate {
        public Long id;
        public BigDecimal unitRate;
    }
}
