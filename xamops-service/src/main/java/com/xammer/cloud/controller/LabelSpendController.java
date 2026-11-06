package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ResourceLabel;
import com.xammer.cloud.repository.ResourceLabelRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.LabelSpendPdfService;
import com.xammer.cloud.service.LabelSpendService;
import com.xammer.cloud.service.cur.CostBasis;
import com.xammer.cloud.service.cur.CurAthenaQueryService;
import com.xammer.cloud.service.cur.ReportCannotAnswerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Map;

/**
 * What each label cost in one month of one account, for the label filter on the Charges by
 * service card. See {@link LabelSpendService} for how resource labels, service labels and their
 * shares divide the bill.
 */
@RestController
@RequestMapping("/api/xamops/label-spend")
public class LabelSpendController {

    private static final Logger logger = LoggerFactory.getLogger(LabelSpendController.class);

    private final LabelSpendService spendService;
    private final LabelSpendPdfService pdfService;
    private final CloudAccountAccessService accessService;
    private final ResourceLabelRepository labelRepository;

    public LabelSpendController(LabelSpendService spendService, LabelSpendPdfService pdfService,
                                CloudAccountAccessService accessService, ResourceLabelRepository labelRepository) {
        this.spendService = spendService;
        this.pdfService = pdfService;
        this.accessService = accessService;
        this.labelRepository = labelRepository;
    }

    /** GET /api/xamops/label-spend/123456789012?period=2026-11&basis=unblended */
    @GetMapping("/{accountId}")
    public LabelSpendService.Spend get(@AuthenticationPrincipal ClientUserDetails user,
                                       @PathVariable String accountId,
                                       @RequestParam(required = false) String period,
                                       @RequestParam(required = false) String basis) {
        return spend(user, accountId, period, basis);
    }

    /** The access check, month parsing and computation both endpoints share. */
    private LabelSpendService.Spend spend(ClientUserDetails user, String accountId, String period, String basis) {
        if (user == null || user.getClientId() == null) {
            throw new AccessDeniedException("Authentication required.");
        }
        CloudAccount account = accessService.requireAwsAccess(user, accountId);
        YearMonth month;
        try {
            month = period == null || period.isBlank() ? YearMonth.now() : YearMonth.parse(period.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Expected period as YYYY-MM, got '" + period + "'.");
        }
        return spendService.spend(user.getClientId(), account, month, CostBasis.from(basis));
    }

    /**
     * The same month as a PDF: every label, or one with {@code labelId}.
     *
     * GET /api/xamops/label-spend/123456789012/export.pdf?period=2026-11&basis=unblended&labelId=3
     */
    @GetMapping(value = "/{accountId}/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@AuthenticationPrincipal ClientUserDetails user,
                                            @PathVariable String accountId,
                                            @RequestParam(required = false) String period,
                                            @RequestParam(required = false) String basis,
                                            @RequestParam(required = false) Long labelId,
                                            @RequestParam(required = false) String currency) {
        LabelSpendService.Spend spend = spend(user, accountId, period, basis);
        if (!"OK".equals(spend.status())) {
            throw new IllegalStateException(spend.message() != null ? spend.message()
                    : "No Cost and Usage Report covers this month.");
        }
        // Looked up rather than taken from the spend, which only lists labels that are on something;
        // and refused unless it is one of the caller's labels.
        ResourceLabel label = labelId == null ? null : labelRepository.findByIdAndClientId(labelId, user.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));
        byte[] pdf = pdfService.pdf(spend, accountId, labelId, label == null ? null : label.getName(), currency,
                user.getUsername());

        String scope = label == null ? "all-labels" : slug(label.getName());
        String file = "label-spend-" + slug(accountId) + "-" + spend.period() + "-" + scope + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Safe in a Content-Disposition filename: letters, digits and dashes only. */
    private static String slug(String s) {
        String out = s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (out.length() > 40) out = out.substring(0, 40);
        return out.isEmpty() ? "label" : out;
    }

    /** 404, not 403, so the endpoint cannot be used to learn which account ids exist. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(404).body(Map.of("error", "Not found."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(ReportCannotAnswerException.class)
    public ResponseEntity<Map<String, String>> cannotAnswer(ReportCannotAnswerException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    /** Raised by the line reader when a month has too many resource lines to read. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> tooLarge(IllegalStateException e) {
        logger.warn("[LabelSpend] {}", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({CurAthenaQueryService.CurQueryException.class,
            software.amazon.awssdk.core.exception.SdkException.class})
    public ResponseEntity<Map<String, String>> readFailed(RuntimeException e) {
        logger.warn("[LabelSpend] Report read failed: {}", e.getMessage());
        return ResponseEntity.status(502).body(Map.of(
                "error", "Could not read the Cost and Usage Report: " + e.getMessage()));
    }
}
