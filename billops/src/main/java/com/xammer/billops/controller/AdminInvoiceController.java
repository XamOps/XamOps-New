package com.xammer.billops.controller;

import com.xammer.cloud.domain.Invoice;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.InvoiceUpdateDto; // ADDED IMPORT
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.service.InvoiceManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional; // --- ADDED IMPORT ---
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/invoices")
public class AdminInvoiceController {

    private final InvoiceManagementService invoiceManagementService;
    private final InvoiceRepository invoiceRepository; // Still needed for other methods if not moved

    public AdminInvoiceController(InvoiceManagementService invoiceManagementService, InvoiceRepository invoiceRepository) {
        this.invoiceManagementService = invoiceManagementService;
        this.invoiceRepository = invoiceRepository;
    }

    @PostMapping("/generate")
    public ResponseEntity<InvoiceDto> generateDraftInvoice(@RequestBody Map<String, Object> payload) {
        String accountId = (String) payload.get("accountId");
        int year = (Integer) payload.get("year");
        int month = (Integer) payload.get("month");

        Invoice draftInvoice = invoiceManagementService.generateDraftInvoice(accountId, year, month);
        return ResponseEntity.ok(InvoiceDto.fromEntity(draftInvoice));
    }

    // --- MODIFIED: This is now the FRESH endpoint ---
    @GetMapping
    public ResponseEntity<List<InvoiceDto>> getFreshAllInvoices() {
        // Calls the @CachePut method
        List<InvoiceDto> invoices = invoiceManagementService.getAllInvoicesAndCache();
        return ResponseEntity.ok(invoices);
    }

    // --- NEW: This is the CACHED endpoint ---
    @GetMapping("/cached")
    public ResponseEntity<List<InvoiceDto>> getCachedAllInvoices() {
        // Calls the @Cacheable method
        Optional<List<InvoiceDto>> cachedInvoices = invoiceManagementService.getCachedAllInvoices();
        return cachedInvoices
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // --- START: MODIFICATION FOR ADMIN CACHING FIX ---
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDto> getInvoiceById(@PathVariable Long id) {
        // CHANGED: The service now returns the DTO directly, which is cache-safe.
        InvoiceDto invoiceDto = invoiceManagementService.getInvoiceForAdmin(id);
        return ResponseEntity.ok(invoiceDto);
    }
    // --- END: MODIFICATION FOR ADMIN CACHING FIX ---

    // --- START: NEW ENDPOINT TO SAVE CHANGES ---
    @PutMapping("/{id}")
    public ResponseEntity<InvoiceDto> updateInvoice(@PathVariable Long id, @RequestBody InvoiceUpdateDto invoiceUpdateDto) {
        Invoice updatedInvoice = invoiceManagementService.updateInvoice(id, invoiceUpdateDto);
        return ResponseEntity.ok(InvoiceDto.fromEntity(updatedInvoice));
    }
    // --- END: NEW ENDPOINT ---

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