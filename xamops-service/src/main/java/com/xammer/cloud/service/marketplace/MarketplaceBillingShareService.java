package com.xammer.cloud.service.marketplace;

import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.service.EmailIcons;
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
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>AWS Billing Data Ready</title></head>" +
               "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>" +
               "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>" +
               "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>" +
               "<tr><td style='background:#000000;padding:40px;'>" +
               "  <div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>" +
               "  <div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.download(40) + "</div>" +
               "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>AWS Billing Data Ready</h1>" +
               "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Your billing CSV is available for download</p>" +
               "</td></tr>" +
               "<tr><td style='padding:32px 40px;'>" +
               "<p style='color:#374151;font-size:15px;margin-top:0'>Hello,</p>" +
               "<p style='color:#374151;font-size:15px'>Your AWS billing data for the following account is ready for download:</p>" +

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
                  "style='display:inline-block;background:#000000;color:#ffffff;" +
                         "padding:14px 40px;border-radius:6px;text-decoration:none;" +
                         "font-weight:700;font-size:15px;letter-spacing:0.025em'>" +
                  "<span style='vertical-align:middle;margin-right:6px;'>" + EmailIcons.download(16) + "</span>Download Billing CSV" +
               "</a>" +
               "</div>" +

               // Expiry warning
               "<div style='background:#fffbeb;border:1px solid #fcd34d;border-radius:6px;" +
                       "padding:12px 16px;margin:20px 0'>" +
               "<p style='margin:0;color:#92400e;font-size:13px'>" +
                       "<span style='vertical-align:middle;margin-right:4px;'>" + EmailIcons.warning(16) + "</span><strong>This link expires in 48 hours.</strong> " +
                       "Please download your file before it expires. " +
                       "If you need a new link, contact your account manager.</p>" +
               "</div>" +

               "<p style='color:#6b7280;font-size:13px'>" +
                       "The CSV file contains usage-type level cost data including usage quantities " +
                       "(e.g. hours, GB) and can be opened in Microsoft Excel or Google Sheets.</p>" +
               "</td></tr>" +
               "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>" +
               "  <span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>" +
               "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>" +
               "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>" +
               "</td></tr>" +
               "</table></td></tr></table></body></html>";
    }
}
