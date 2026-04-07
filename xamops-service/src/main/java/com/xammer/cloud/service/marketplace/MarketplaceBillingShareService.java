package com.xammer.cloud.service.marketplace;

import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class MarketplaceBillingShareService {

    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final MarketplacePayerAccountRepository payerAccountRepository;
    private final MarketplaceBillingExtractionService extractionService;
    private final FileStorageService fileStorageService;
    private final EmailService emailService;

    public MarketplaceBillingShareService(MarketplaceAccountRepository marketplaceAccountRepository,
                                           MarketplacePayerAccountRepository payerAccountRepository,
                                           MarketplaceBillingExtractionService extractionService,
                                           FileStorageService fileStorageService,
                                           EmailService emailService) {
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.payerAccountRepository = payerAccountRepository;
        this.extractionService = extractionService;
        this.fileStorageService = fileStorageService;
        this.emailService = emailService;
    }

    /**
     * Share billing data for a specific month of a single marketplace account.
     * 1. Extracts all months from Cost Explorer if the requested month is not in S3.
     * 2. Generates a 48-hour pre-signed download URL for the requested month.
     * 3. Sends a branded HTML email to the recipient.
     *
     * @param awsAccountId   the marketplace account AWS account ID
     * @param recipientEmail email to send the download link to
     * @param yearMonth      month to share, e.g. "2025-02"
     */
    public void shareBillingData(String awsAccountId, String recipientEmail, String yearMonth) {
        log.info("Sharing billing data for account {} month {} → {}",
                awsAccountId, yearMonth, recipientEmail);

        // Lookup the marketplace account
        MarketplaceAccount account = marketplaceAccountRepository.findByAwsAccountId(awsAccountId)
                .orElseThrow(() -> new RuntimeException(
                        "Marketplace account not found: " + awsAccountId
                        + ". Run discovery first."));

        MarketplacePayerAccount payerAccount = account.getPayerAccount();
        if (payerAccount == null) {
            payerAccount = payerAccountRepository.findAll().stream()
                    .filter(p -> marketplaceAccountRepository
                            .findByPayerAccount(p).stream()
                            .anyMatch(a -> a.getAwsAccountId().equals(awsAccountId)))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Payer account not found for marketplace account " + awsAccountId));
        }

        // Step 1: Ensure the specific month's CSV exists in S3
        String s3Key = MarketplaceBillingExtractionService.monthlyS3Key(awsAccountId, yearMonth);
        if (!fileStorageService.s3KeyExists(s3Key)) {
            log.info("CSV not found in S3 for account {} month {}, extracting all months now...",
                    awsAccountId, yearMonth);
            List<String> uploaded = extractionService.extractForAccount(awsAccountId, payerAccount);
            if (uploaded.isEmpty()) {
                throw new RuntimeException(
                        "No billing data available for account " + awsAccountId
                        + " in the period " + MarketplaceBillingExtractionService.EXTRACTION_START
                        + " to " + MarketplaceBillingExtractionService.EXTRACTION_END);
            }
            if (!fileStorageService.s3KeyExists(s3Key)) {
                throw new RuntimeException(
                        "Billing data for month " + yearMonth + " not found for account " + awsAccountId
                        + ". Available months were extracted but this month has no data.");
            }
        }

        // Step 2: Generate 48-hour pre-signed URL
        String downloadUrl = fileStorageService.generatePresignedUrlWithExpiry(
                s3Key, Duration.ofHours(48));
        log.info("Generated 48h presigned URL for account {} month {}", awsAccountId, yearMonth);

        // Step 3: Send email
        String displayMonth = formatMonth(yearMonth);
        String subject = "Your AWS Billing Data — " + displayMonth + " [" + awsAccountId + "]";
        String htmlBody = buildEmailHtml(awsAccountId, account.getAccountName(), yearMonth, displayMonth, downloadUrl);
        emailService.sendHtmlEmail(recipientEmail, subject, htmlBody);

        log.info("✅ Billing data email sent to {} for account {} month {}",
                recipientEmail, awsAccountId, yearMonth);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Formats "2025-02" → "February 2025" */
    private String formatMonth(String yearMonth) {
        try {
            YearMonth ym = YearMonth.parse(yearMonth, DateTimeFormatter.ofPattern("yyyy-MM"));
            return ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        } catch (Exception e) {
            return yearMonth;
        }
    }

    private String buildEmailHtml(String accountId, String accountName,
                                   String yearMonth, String displayMonth, String downloadUrl) {
        return "<!DOCTYPE html>" +
               "<html><body style='margin:0;padding:0;background:#f5f5f5;font-family:sans-serif'>" +
               "<div style='max-width:600px;margin:40px auto;background:#ffffff;" +
                       "border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.1)'>" +

               // Header
               "<div style='background:linear-gradient(135deg,#6366f1,#8b5cf6);" +
                       "padding:32px 40px;text-align:center'>" +
               "<h1 style='color:#ffffff;margin:0;font-size:24px;font-weight:700'>" +
                       "XamOps BillOps</h1>" +
               "<p style='color:rgba(255,255,255,0.85);margin:8px 0 0'>Billing Data Ready for Download</p>" +
               "</div>" +

               // Body
               "<div style='padding:40px'>" +
               "<p style='color:#374151;font-size:15px;margin-top:0'>Hello,</p>" +
               "<p style='color:#374151;font-size:15px'>Your AWS billing data for the following " +
                       "account is ready for download:</p>" +

               // Account card
               "<div style='background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;" +
                       "padding:16px 20px;margin:20px 0'>" +
               "<p style='margin:0 0 6px;color:#6b7280;font-size:13px;font-weight:600;" +
                       "text-transform:uppercase;letter-spacing:0.05em'>Account</p>" +
               "<p style='margin:0;color:#111827;font-size:16px;font-weight:700'>" +
                       accountName + "</p>" +
               "<p style='margin:4px 0 0;color:#6b7280;font-size:13px'>" + accountId + "</p>" +
               "</div>" +

               // Period card
               "<div style='background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;" +
                       "padding:16px 20px;margin:20px 0'>" +
               "<p style='margin:0 0 6px;color:#6b7280;font-size:13px;font-weight:600;" +
                       "text-transform:uppercase;letter-spacing:0.05em'>Billing Period</p>" +
               "<p style='margin:0;color:#111827;font-size:16px;font-weight:700'>" +
                       displayMonth + "</p>" +
               "<p style='margin:4px 0 0;color:#6b7280;font-size:13px'>" +
                       "Usage-type level breakdown with quantities and costs</p>" +
               "</div>" +

               // Download button
               "<div style='text-align:center;margin:32px 0'>" +
               "<a href='" + downloadUrl + "' " +
                  "style='display:inline-block;background:#6366f1;color:#ffffff;" +
                         "padding:14px 40px;border-radius:6px;text-decoration:none;" +
                         "font-weight:700;font-size:15px;letter-spacing:0.025em'>" +
                  "&#8595;&nbsp; Download Billing CSV" +
               "</a>" +
               "</div>" +

               // Expiry warning
               "<div style='background:#fffbeb;border:1px solid #fcd34d;border-radius:6px;" +
                       "padding:12px 16px;margin:20px 0'>" +
               "<p style='margin:0;color:#92400e;font-size:13px'>" +
                       "&#9888;&nbsp; <strong>This link expires in 48 hours.</strong> " +
                       "Please download your file before it expires. " +
                       "If you need a new link, contact your account manager.</p>" +
               "</div>" +

               "<p style='color:#6b7280;font-size:13px'>" +
                       "The CSV file contains usage-type level cost data including usage quantities " +
                       "(e.g. hours, GB) and can be opened in Microsoft Excel or Google Sheets.</p>" +
               "</div>" +

               // Footer
               "<div style='background:#f9fafb;border-top:1px solid #e5e7eb;" +
                       "padding:20px 40px;text-align:center'>" +
               "<p style='color:#9ca3af;font-size:12px;margin:0'>" +
                       "Sent by XamOps BillOps &nbsp;|&nbsp; " +
                       "If you did not request this, please ignore this email.</p>" +
               "</div>" +

               "</div>" +
               "</body></html>";
    }
}
