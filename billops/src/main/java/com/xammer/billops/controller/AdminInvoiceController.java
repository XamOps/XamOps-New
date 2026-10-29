package com.xammer.billops.controller;

import com.xammer.cloud.domain.Invoice;
import com.xammer.billops.dto.DiscountRequestDto;
import com.xammer.billops.dto.HoldingCostRequestDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.InvoiceUpdateDto;
import com.xammer.billops.dto.PrivatePricingRequestDto;
import com.xammer.billops.service.InvoiceManagementService;
import com.xammer.billops.service.cur.CurUnavailable;
import com.xammer.billops.service.cur.CurUnavailableException;
import com.xammer.billops.service.upload.UploadedBillDraftService;
import com.xammer.billops.service.upload.UploadedBillException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin invoice management: generate drafts, edit them, discount them, finalize them.
 *
 * <p><b>Authorization note.</b> This class carries no role restriction, so every endpoint
 * without its own {@code @PreAuthorize} is reachable by any authenticated BillOps user.
 * That predates the private pricing endpoints below, which do restrict themselves; closing
 * the gap for the rest means deciding what each existing caller is entitled to, and is
 * left as its own change rather than folded into this one.
 */
@RestController
@RequestMapping("/api/admin/invoices")
public class AdminInvoiceController {

    private static final Logger logger = LoggerFactory.getLogger(AdminInvoiceController.class);
    private final InvoiceManagementService invoiceManagementService;
    private final UploadedBillDraftService uploadedBillDraftService;

    public AdminInvoiceController(InvoiceManagementService invoiceManagementService,
                                  UploadedBillDraftService uploadedBillDraftService) {
        this.invoiceManagementService = invoiceManagementService;
        this.uploadedBillDraftService = uploadedBillDraftService;
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

    // ─── Upload draft (AWS bill PDF) ──────────────────────────────────────────
    //
    // Restricted like the private-pricing endpoints: the uploaded figures become what the
    // customer is billed.

    /**
     * Reads an AWS console bill PDF and reports what a draft built from it would hold --
     * account, billing period, charges by service, and anything doubtful. Creates nothing.
     */
    @PostMapping(value = "/upload-draft/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> previewUploadedDraft(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(uploadedBillDraftService.preview(file));
        } catch (UploadedBillException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Creates a draft from an AWS console bill PDF. {@code accountId}, {@code year} and
     * {@code month} are what the admin confirmed in the preview; any omitted fall back to
     * what the PDF says. The draft is editable like any generated one.
     *
     * <p>An account ID BillOps doesn't know is added as a billing-only AWS account, named
     * {@code accountName}, under {@code clientId} or a client called {@code clientName}
     * (found or created). All three are optional and default to "AWS account &lt;id&gt;".
     */
    @PostMapping(value = "/upload-draft", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> createUploadedDraft(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "accountId", required = false) String accountId,
                                                 @RequestParam(value = "year", required = false) Integer year,
                                                 @RequestParam(value = "month", required = false) Integer month,
                                                 @RequestParam(value = "includeIrn", defaultValue = "false") boolean includeIrn,
                                                 @RequestParam(value = "accountName", required = false) String accountName,
                                                 @RequestParam(value = "clientId", required = false) Long clientId,
                                                 @RequestParam(value = "clientName", required = false) String clientName) {
        try {
            Invoice draft = uploadedBillDraftService.createDraft(file, accountId, year, month, includeIrn,
                    new UploadedBillDraftService.NewAccountDetails(accountName, clientId, clientName));
            return ResponseEntity.ok(InvoiceDto.fromEntity(draft));
        } catch (UploadedBillException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
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

    /**
     * Sets or clears the editable, optional holding-cost markup on a draft invoice's AWS
     * Marketplace charge. {@code PUT} because it sets a single value idempotently, like the
     * discount endpoint; its own path because a holding cost is a markup, not a discount.
     *
     * <p>400 when the percent is out of the [0, 100] range; 409 when the invoice is not a
     * draft. The message is written to be shown as-is. Restricted to full BillOps admins and
     * super admins, matching the private-pricing endpoints -- setting what a customer is
     * billed is the same class of action.
     */
    @PutMapping("/{id}/holding-cost")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> setHoldingCost(@PathVariable Long id,
                                            @RequestBody HoldingCostRequestDto request) {
        try {
            Invoice updated = invoiceManagementService.setMarketplaceHoldingCost(id, request.getPercent());
            return ResponseEntity.ok(InvoiceDto.fromEntity(updated));
        } catch (IllegalArgumentException e) { // out of range
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) { // not a draft
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    // ─── Manual private pricing ──────────────────────────────────────────────
    //
    // Bills part of a draft at a rate the admin sets instead of at provider cost, and
    // optionally shields those charges from the invoice's overall discount.
    //
    // Restricted to full BillOps admins and super admins, the same scope as
    // AdminCloudFrontController, which manages the automatic CloudFront equivalent.
    // Setting what a customer is charged is not something the rest of this controller's
    // audience should be able to do, and these are new endpoints, so nothing that works
    // today is narrowed by saying so. (The endpoints above carry no authorization
    // annotation at all -- see the note on the class.)

    /**
     * Applies a rate to a service, a service in one region, or a single usage type.
     *
     * <p>400 when the request cannot be honoured at all — no such service on the invoice,
     * no rate given. 409 when the invoice's own state is what conflicts: already finalized,
     * a rate already covers these charges, the scope spans several units, or nothing in it
     * carries usage a per-unit rate can price. The message is written to be shown as-is.
     */
    @PostMapping("/{id}/private-pricing")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> applyPrivatePricing(@PathVariable Long id,
                                                 @RequestBody PrivatePricingRequestDto request) {
        try {
            Invoice updated = invoiceManagementService.applyPrivatePricing(id, request);
            return ResponseEntity.ok(InvoiceDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            logger.info("[PrivatePricing] Rejected for invoice {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            logger.info("[PrivatePricing] Conflict for invoice {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Turns the overall-discount shield on or off for one applied rate. */
    @PatchMapping("/{invoiceId}/private-pricing/{pricingId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> setPrivatePricingExclusion(@PathVariable Long invoiceId,
                                                        @PathVariable Long pricingId,
                                                        @RequestBody Map<String, Object> payload) {
        Object value = payload.get("excludeFromOverallDiscount");
        if (value == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "excludeFromOverallDiscount is required."));
        }
        try {
            Invoice updated = invoiceManagementService.setPrivatePricingDiscountExclusion(
                    invoiceId, pricingId, Boolean.parseBoolean(String.valueOf(value)));
            return ResponseEntity.ok(InvoiceDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Removes an applied rate, putting its charges back to provider cost. */
    @DeleteMapping("/{invoiceId}/private-pricing/{pricingId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> removePrivatePricing(@PathVariable Long invoiceId,
                                                  @PathVariable Long pricingId) {
        try {
            Invoice updated = invoiceManagementService.removePrivatePricing(invoiceId, pricingId);
            return ResponseEntity.ok(InvoiceDto.fromEntity(updated));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Optional finalize body carrying the admin-chosen USD→INR rate to lock in. */
    public record FinalizeRequest(Double exchangeRate) {}

    @PostMapping("/{id}/finalize")
    public ResponseEntity<?> finalizeInvoice(@PathVariable Long id,
                                             @RequestBody(required = false) FinalizeRequest body) {
        Double rate = body != null ? body.exchangeRate() : null;
        try {
            Invoice finalizedInvoice = invoiceManagementService.finalizeInvoice(id, rate);
            return ResponseEntity.ok(InvoiceDto.fromEntity(finalizedInvoice));
        } catch (IllegalArgumentException e) {
            logger.info("[InvoiceGen] Finalize rejected for invoice {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
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