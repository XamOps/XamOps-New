package com.xammer.billops.controller;

import com.xammer.cloud.domain.Invoice;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.InvoiceUpdateDto;
import com.xammer.billops.service.InvoiceManagementService;
import com.xammer.billops.service.cur.CurUnavailable;
import com.xammer.billops.service.cur.CurUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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

    /**
     * Generates a draft invoice.
     *
     * <p>Body: {@code accountId}, {@code year}, {@code month}, {@code includeIrn}, and
     * optionally {@code source} — {@code "CUR"} (the default) or {@code "COST_EXPLORER"}.
     *
     * <p>An AWS draft is read from the customer's Cost and Usage Report unless the
     * request says otherwise. When the report cannot serve the month, the answer is a
     * 409 naming the reason, and nothing has been read from Cost Explorer:
     * <pre>
     *   { "error": "CUR_UNAVAILABLE", "reason": "PERIOD_BEFORE_REPORT_START",
     *     "message": "...", "canUseCostExplorer": true }
     * </pre>
     * The page shows that message with a button that repeats the request with
     * {@code source: "COST_EXPLORER"}. That second request is the only way this endpoint
     * ever reaches Cost Explorer for an AWS account.
     *
     * <p>409 rather than 4xx-as-error or 5xx: nothing is wrong with the request or the
     * server. The account's current state conflicts with the source asked for, and a
     * different choice by the caller resolves it.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateDraftInvoice(@RequestBody Map<String, Object> payload) {
        String accountId = (String) payload.get("accountId");
        int year = Integer.parseInt(String.valueOf(payload.get("year")));
        int month = Integer.parseInt(String.valueOf(payload.get("month")));
        boolean includeIrn = Boolean.TRUE.equals(payload.get("includeIrn"));

        Invoice.DataSource source;
        try {
            source = parseSource(payload.get("source"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        try {
            Invoice draftInvoice = invoiceManagementService.generateDraftInvoice(
                    accountId, year, month, includeIrn, source);
            return ResponseEntity.ok(InvoiceDto.fromEntity(draftInvoice));
        } catch (CurUnavailableException e) {
            logger.info("[InvoiceGen] CUR cannot serve {} {}-{}: {} — {}",
                    accountId, year, month, e.getReason(), e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "CUR_UNAVAILABLE");
            body.put("reason", e.getReason().name());
            body.put("message", e.getMessage());
            // A month that has not started has no data anywhere, so offering Cost
            // Explorer would only produce an empty invoice.
            body.put("canUseCostExplorer", e.getReason() != CurUnavailable.PERIOD_IN_FUTURE);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    /**
     * Reads the requested source, defaulting to the report.
     *
     * <p>Absent means CUR, so a caller that predates this parameter gets the new
     * behaviour — reading the report first and asking before Cost Explorer — rather
     * than silently keeping the old one. An unrecognised value is refused instead of
     * guessed at: guessing "CE" for a typo would be exactly the unrequested Cost
     * Explorer call this endpoint exists to prevent.
     */
    private Invoice.DataSource parseSource(Object raw) {
        if (raw == null) {
            return Invoice.DataSource.CUR;
        }
        String value = String.valueOf(raw).trim().toUpperCase();
        switch (value) {
            case "", "CUR":
                return Invoice.DataSource.CUR;
            case "CE", "COST_EXPLORER":
                return Invoice.DataSource.COST_EXPLORER;
            default:
                throw new IllegalArgumentException(
                        "Unknown source '" + raw + "'. Use CUR or COST_EXPLORER.");
        }
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInvoice(@PathVariable Long id) {
        try {
            invoiceManagementService.deleteInvoice(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            logger.warn("Cannot delete invoice {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * NEW: Merge two draft invoices into one.
     * Moves items from the 'source' invoice (e.g., CloudFront) into the 'target' invoice (e.g., Standard Bill).
     */
    @PostMapping("/merge")
    public ResponseEntity<InvoiceDto> mergeInvoices(
            @RequestParam Long targetInvoiceId, 
            @RequestParam Long sourceInvoiceId) {
        
        logger.info("Request to merge invoice {} into invoice {}", sourceInvoiceId, targetInvoiceId);
        try {
            Invoice mergedInvoice = invoiceManagementService.mergeDraftInvoices(targetInvoiceId, sourceInvoiceId);
            return ResponseEntity.ok(InvoiceDto.fromEntity(mergedInvoice));
        } catch (Exception e) {
            logger.error("Error merging invoices: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build(); // Or return specific error message DTO
        }
    }
}