package com.xammer.billops.controller;

import com.xammer.cloud.domain.Invoice;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.InvoiceUpdateDto;
import com.xammer.billops.service.InvoiceManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/invoices")
public class AdminInvoiceController {

    private static final Logger logger = LoggerFactory.getLogger(AdminInvoiceController.class);
    private final InvoiceManagementService invoiceManagementService;

    public AdminInvoiceController(InvoiceManagementService invoiceManagementService) {
        this.invoiceManagementService = invoiceManagementService;
    }

    @PostMapping("/generate")
    public ResponseEntity<InvoiceDto> generateDraftInvoice(@RequestBody Map<String, Object> payload) {
        String accountId = (String) payload.get("accountId");
        
        // FIX: Safely parse integers from the payload to handle both String ("2025") and Integer (2025) inputs
        int year = Integer.parseInt(String.valueOf(payload.get("year")));
        int month = Integer.parseInt(String.valueOf(payload.get("month")));

        Invoice draftInvoice = invoiceManagementService.generateDraftInvoice(accountId, year, month);
        return ResponseEntity.ok(InvoiceDto.fromEntity(draftInvoice));
    }

    /**
     * Unified endpoint for Invoice List.
     * Supports "Instant Load" via caching and manual refresh.
     * GET /api/admin/invoices?forceRefresh=true|false
     */
    @GetMapping
    public ResponseEntity<List<InvoiceDto>> getAllInvoices(@RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.debug("GET /api/admin/invoices called. ForceRefresh: {}", forceRefresh);

        // 1. Try Cache (if not forced)
        if (!forceRefresh) {
            Optional<List<InvoiceDto>> cachedInvoices = invoiceManagementService.getCachedAllInvoices();
            if (cachedInvoices.isPresent()) {
                logger.debug("Returning CACHED invoice list");
                return ResponseEntity.ok(cachedInvoices.get());
            }
        }

        // 2. Fetch Fresh Data (if forced or cache miss)
        logger.info("Fetching FRESH invoice list and updating cache");
        List<InvoiceDto> freshInvoices = invoiceManagementService.getAllInvoicesAndCache();
        return ResponseEntity.ok(freshInvoices);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDto> getInvoiceById(@PathVariable Long id) {
        // Uses the service method that returns DTO directly (cache-safe)
        InvoiceDto invoiceDto = invoiceManagementService.getInvoiceForAdmin(id);
        return ResponseEntity.ok(invoiceDto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<InvoiceDto> updateInvoice(@PathVariable Long id, @RequestBody InvoiceUpdateDto invoiceUpdateDto) {
        Invoice updatedInvoice = invoiceManagementService.updateInvoice(id, invoiceUpdateDto);
        return ResponseEntity.ok(InvoiceDto.fromEntity(updatedInvoice));
    }

    @PutMapping("/{id}/discount")
    public ResponseEntity<InvoiceDto> applyDiscount(@PathVariable Long id, @RequestBody DiscountRequestDto discountRequest) {
        Invoice updatedInvoice = invoiceManagementService.applyDiscountToInvoice(id, discountRequest.getServiceName(), discountRequest.getPercentage());
        return ResponseEntity.ok(InvoiceDto.fromEntity(updatedInvoice));
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<InvoiceDto> finalizeInvoice(@PathVariable Long id) {
        Invoice finalizedInvoice = invoiceManagementService.finalizeInvoice(id);
        return ResponseEntity.ok(InvoiceDto.fromEntity(finalizedInvoice));
    }

    @DeleteMapping("/{invoiceId}/discounts/{discountId}")
    public ResponseEntity<InvoiceDto> removeDiscount(@PathVariable Long invoiceId, @PathVariable Long discountId) {
        Invoice updatedInvoice = invoiceManagementService.removeDiscountFromInvoice(invoiceId, discountId);
        return ResponseEntity.ok(InvoiceDto.fromEntity(updatedInvoice));
    }
}