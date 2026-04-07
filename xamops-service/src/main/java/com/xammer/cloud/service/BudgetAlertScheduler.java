package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Daily scheduler that checks every account's budgets and sends XamOps-branded
 * threshold emails when actual or forecasted spend crosses 50 / 80 / 100 %.
 *
 * Runs once per day at 08:00 UTC so users see alerts at the start of their workday.
 */
@Service
public class BudgetAlertScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BudgetAlertScheduler.class);

    private static final double[] ACTUAL_THRESHOLDS     = {50.0, 80.0, 100.0};
    private static final double   FORECAST_THRESHOLD    = 100.0;

    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final FinOpsService finOpsService;
    private final EmailService emailService;
    private final BudgetEmailBuilder budgetEmailBuilder;

    public BudgetAlertScheduler(
            CloudAccountRepository cloudAccountRepository,
            UserRepository userRepository,
            FinOpsService finOpsService,
            EmailService emailService,
            BudgetEmailBuilder budgetEmailBuilder) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.finOpsService = finOpsService;
        this.emailService = emailService;
        this.budgetEmailBuilder = budgetEmailBuilder;
    }

    @Scheduled(cron = "0 0 8 * * *") // 08:00 UTC daily
    public void checkBudgetAlerts() {
        logger.info("[BudgetAlertScheduler] Starting daily budget alert check...");

        List<CloudAccount> accounts = cloudAccountRepository.findAll();
        for (CloudAccount account : accounts) {
            try {
                checkAccountBudgets(account);
            } catch (Exception e) {
                logger.warn("[BudgetAlertScheduler] Failed to check budgets for account {}: {}",
                        account.getAwsAccountId(), e.getMessage());
            }
        }

        logger.info("[BudgetAlertScheduler] Daily budget alert check complete.");
    }

    private void checkAccountBudgets(CloudAccount account) throws Exception {
        List<BudgetDetails> budgets = finOpsService
                .getAccountBudgets(account, false)
                .get();

        if (budgets.isEmpty()) return;

        // Find the primary user for this account's client to email
        String recipientEmail = resolveRecipientEmail(account);
        if (recipientEmail == null) {
            logger.debug("[BudgetAlertScheduler] No recipient email for account {}, skipping.",
                    account.getAwsAccountId());
            return;
        }

        String accountName = account.getAccountName() != null ? account.getAccountName() : account.getAwsAccountId();

        for (BudgetDetails budget : budgets) {
            if (budget.getBudgetLimit() == null || budget.getBudgetLimit().compareTo(BigDecimal.ZERO) == 0) continue;

            double actualPct  = pct(budget.getActualSpend(),     budget.getBudgetLimit());
            double forecastPct = pct(budget.getForecastedSpend(), budget.getBudgetLimit());

            // Check actual thresholds (50, 80, 100)
            for (double threshold : ACTUAL_THRESHOLDS) {
                if (actualPct >= threshold) {
                    sendThresholdEmail(recipientEmail, budget, accountName, account.getAwsAccountId(),
                            threshold, false);
                    break; // send only the highest breached threshold per run
                }
            }

            // Check forecasted overage separately
            if (forecastPct >= FORECAST_THRESHOLD) {
                sendThresholdEmail(recipientEmail, budget, accountName, account.getAwsAccountId(),
                        FORECAST_THRESHOLD, true);
            }
        }
    }

    private void sendThresholdEmail(String email, BudgetDetails budget, String accountName,
                                    String accountId, double threshold, boolean isForecasted) {
        try {
            String html = budgetEmailBuilder.buildBudgetThresholdEmail(
                    budget, accountName, accountId, threshold, isForecasted);
            String subject = isForecasted
                    ? "XamOps Budget Forecast: " + budget.getBudgetName() + " may exceed limit"
                    : String.format("XamOps Budget Alert: %s reached %.0f%% of limit",
                            budget.getBudgetName(), threshold);
            emailService.sendHtmlEmail(email, subject, html);
            logger.info("[BudgetAlertScheduler] Sent {}% {} alert for budget '{}' to {}",
                    (int) threshold, isForecasted ? "forecast" : "actual", budget.getBudgetName(), email);
        } catch (Exception e) {
            logger.warn("[BudgetAlertScheduler] Failed to send alert email to {}: {}", email, e.getMessage());
        }
    }

    /**
     * Resolves the email recipient for an account.
     * Uses the first user belonging to the account's client whose email is set,
     * falling back to username (since login supports email-as-username).
     */
    private String resolveRecipientEmail(CloudAccount account) {
        if (account.getClient() == null) return null;
        List<User> users = userRepository.findByClientId(account.getClient().getId());
        for (User u : users) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) return u.getEmail();
            // username may itself be an email
            if (u.getUsername() != null && u.getUsername().contains("@")) return u.getUsername();
        }
        return null;
    }

    private double pct(BigDecimal spend, BigDecimal limit) {
        if (spend == null || limit == null || limit.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return spend.divide(limit, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
    }
}
