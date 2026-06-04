package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MonthlyDigestService {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyDigestService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final FinOpsService finOpsService;
    private final AwsSecurityHubService awsSecurityHubService;
    private final XamOpsRightsizingService xamOpsRightsizingService;
    private final EbsDiskRightsizingService ebsDiskRightsizingService;
    private final MonthlyDigestEmailBuilder emailBuilder;
    private final EmailService emailService;

    public MonthlyDigestService(CloudAccountRepository cloudAccountRepository,
                                UserRepository userRepository,
                                FinOpsService finOpsService,
                                AwsSecurityHubService awsSecurityHubService,
                                XamOpsRightsizingService xamOpsRightsizingService,
                                EbsDiskRightsizingService ebsDiskRightsizingService,
                                MonthlyDigestEmailBuilder emailBuilder,
                                EmailService emailService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.finOpsService = finOpsService;
        this.awsSecurityHubService = awsSecurityHubService;
        this.xamOpsRightsizingService = xamOpsRightsizingService;
        this.ebsDiskRightsizingService = ebsDiskRightsizingService;
        this.emailBuilder = emailBuilder;
        this.emailService = emailService;
    }

    public void runDigest() {
        LocalDate today     = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);
        String start        = startDate.format(ISO);
        String end          = today.format(ISO);
        String dateRange    = startDate.format(DateTimeFormatter.ofPattern("MMMM d"))
                              + " – "
                              + today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));

        logger.info("[MonthlyDigest] Starting monthly cloud digest for period {} to {}", start, end);

        List<CloudAccount> awsAccounts = cloudAccountRepository.findByProvider("AWS");
        logger.info("[MonthlyDigest] Found {} AWS accounts to process", awsAccounts.size());

        for (CloudAccount account : awsAccounts) {
            String accountId   = account.getProviderAccountId();
            String accountName = account.getAccountName();
            try {
                processAccount(account, accountId, accountName, start, end, dateRange);
            } catch (Exception e) {
                logger.warn("[MonthlyDigest] Failed to process account {}: {}", accountId, e.getMessage());
            }
        }

        logger.info("[MonthlyDigest] Monthly digest complete.");
    }

    private void processAccount(CloudAccount account, String accountId, String accountName,
                                String start, String end, String dateRange) throws Exception {
        logger.info("[MonthlyDigest] Processing account {}", accountId);

        FinOpsReportDto report = finOpsService.getFinOpsReport(accountId, false, start, end).join();
        List<AwsSecurityFinding> security = awsSecurityHubService.getSecurityFindings(accountId, false).join();
        List<XamOpsRightsizingRecommendation> ec2Recs = xamOpsRightsizingService.getLiveRecommendations(accountId, false);
        List<Map<String, Object>> diskRecs = ebsDiskRightsizingService.getRecommendations(accountId, false);

        String subject = String.format("Your Monthly Cloud Digest — %s — %s",
                accountName, monthLabel(dateRange));
        String htmlBody = emailBuilder.build(report, security, ec2Recs, diskRecs, accountName, dateRange);

        List<String> recipients = resolveRecipients(account);
        logger.info("[MonthlyDigest] Sending digest for {} to {} recipients", accountName, recipients.size());

        for (String email : recipients) {
            try {
                emailService.sendHtmlEmail(email, subject, htmlBody);
                logger.info("[MonthlyDigest] Sent to {} for account {}", email, accountName);
            } catch (Exception e) {
                logger.error("[MonthlyDigest] Failed to send to {}: {}", email, e.getMessage());
            }
        }
    }

    private List<String> resolveRecipients(CloudAccount account) {
        if (account.getClient() == null) return List.of();
        return userRepository.findByClientId(account.getClient().getId()).stream()
                .map(u -> {
                    if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
                    if (u.getUsername() != null && u.getUsername().contains("@")) return u.getUsername();
                    return null;
                })
                .filter(e -> e != null)
                .distinct()
                .collect(Collectors.toList());
    }

    private String monthLabel(String dateRange) {
        // Extract "June 2026" from "June 1 – June 30, 2026"
        String[] parts = dateRange.split("–");
        if (parts.length == 2) {
            String endPart = parts[1].trim();
            String[] dateParts = endPart.split(" ");
            if (dateParts.length >= 3) {
                return dateParts[0] + " " + dateParts[2].replace(",", "");
            }
        }
        return dateRange;
    }
}
