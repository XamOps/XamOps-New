package com.xammer.billops.controller;

import com.xammer.billops.domain.Invoice;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.service.InvoiceManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/invoices")
@PreAuthorize("hasRole('ROLE_BILLOPS_ADMIN')") // Security enabled
public class AdminInvoiceController {

    private final InvoiceManagementService invoiceManagementService;
    private final InvoiceRepository invoiceRepository;

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

    @GetMapping
    public ResponseEntity<List<InvoiceDto>> getAllInvoices() {
        List<Invoice> invoices = invoiceRepository.findAll();
        return ResponseEntity.ok(invoices.stream().map(InvoiceDto::fromEntity).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDto> getInvoiceById(@PathVariable Long id) {
        Invoice invoice = invoiceManagementService.getInvoiceForAdmin(id);
        return ResponseEntity.ok(InvoiceDto.fromEntity(invoice));
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
}