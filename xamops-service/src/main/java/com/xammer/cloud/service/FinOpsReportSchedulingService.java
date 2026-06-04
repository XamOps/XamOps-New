package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Service
public class FinOpsReportSchedulingService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsReportSchedulingService.class);

    private final FinOpsReportScheduleRepository scheduleRepository;
    private final CostService costService;
    private final GcpCostService gcpCostService;
    private final EmailService emailService;
    private final FinOpsReportEmailBuilder emailBuilder;
    private final EbsDiskRightsizingService ebsDiskRightsizingService;

    public FinOpsReportSchedulingService(FinOpsReportScheduleRepository scheduleRepository,
            CostService costService,
            GcpCostService gcpCostService,
            EmailService emailService,
            FinOpsReportEmailBuilder emailBuilder,
            EbsDiskRightsizingService ebsDiskRightsizingService) {
        this.scheduleRepository = scheduleRepository;
        this.costService = costService;
        this.gcpCostService = gcpCostService;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
        this.ebsDiskRightsizingService = ebsDiskRightsizingService;
    }

    // Scheduled report time: 6:00 PM IST (12:30 UTC)
    private static final LocalTime SCHEDULED_TIME = LocalTime.of(18, 0);

    /**
     * Checks for and runs any missed report executions on application startup.
     * This handles cases where the application was suspended/sleeping during
     * the scheduled execution time.
     *
     * Uses ApplicationReadyEvent instead of @PostConstruct to ensure the
     * transaction manager and Hibernate session are fully initialized.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void checkAndRunMissedReports() {
        logger.info("========================================");
        logger.info("Checking for missed FinOps report executions...");
        logger.info("========================================");

        LocalDateTime now = LocalDateTime.now();
        LocalTime currentTime = now.toLocalTime();
        LocalDate today = now.toLocalDate();

        // Only check for missed reports if we're past the scheduled time
        if (currentTime.isBefore(SCHEDULED_TIME)) {
            logger.info("Current time {} is before scheduled time {}. No missed reports to check.",
                    currentTime, SCHEDULED_TIME);
            return;
        }

        // Check DAILY reports
        checkAndRunMissedDailyReports(today);

        // Check WEEKLY reports (only on Fridays or if missed on a previous Friday)
        checkAndRunMissedWeeklyReports(today);

        // Check MONTHLY reports (only on 30th or if missed)
        checkAndRunMissedMonthlyReports(today);

        logger.info("========================================");
        logger.info("Missed report check completed.");
        logger.info("========================================");
    }

    private void checkAndRunMissedDailyReports(LocalDate today) {
        List<FinOpsReportSchedule> dailySchedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.DAILY);

        if (dailySchedules.isEmpty()) {
            logger.info("No active DAILY schedules found.");
            return;
        }

        // Check if any daily schedule was missed today
        boolean anyMissed = dailySchedules.stream().anyMatch(schedule -> {
            LocalDateTime lastSent = schedule.getLastSent();
            // Missed if never sent OR last sent before today
            return lastSent == null || lastSent.toLocalDate().isBefore(today);
        });

        if (anyMissed) {
            logger.info(">>> DETECTED MISSED DAILY REPORTS - Triggering recovery execution...");
            runDailyReports();
        } else {
            logger.info("All DAILY reports for today have already been sent.");
        }
    }

    private void checkAndRunMissedWeeklyReports(LocalDate today) {
        // Find the most recent Friday (including today if it's Friday)
        LocalDate lastFriday = today;
        while (lastFriday.getDayOfWeek() != DayOfWeek.FRIDAY) {
            lastFriday = lastFriday.minusDays(1);
        }

        List<FinOpsReportSchedule> weeklySchedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.WEEKLY);

        if (weeklySchedules.isEmpty()) {
            logger.info("No active WEEKLY schedules found.");
            return;
        }

        final LocalDate fridayToCheck = lastFriday;
        boolean anyMissed = weeklySchedules.stream().anyMatch(schedule -> {
            LocalDateTime lastSent = schedule.getLastSent();
            // Missed if never sent OR last sent before the most recent Friday
            return lastSent == null || lastSent.toLocalDate().isBefore(fridayToCheck);
        });

        if (anyMissed) {
            logger.info(">>> DETECTED MISSED WEEKLY REPORTS (for Friday {}) - Triggering recovery execution...", lastFriday);
            runWeeklyReports();
        } else {
            logger.info("All WEEKLY reports for the week ending {} have already been sent.", lastFriday);
        }
    }

    private void checkAndRunMissedMonthlyReports(LocalDate today) {
        // Monthly reports run on the 30th
        int dayOfMonth = today.getDayOfMonth();

        // Find the most recent 30th (could be this month or last month)
        LocalDate lastThirtieth;
        if (dayOfMonth >= 30) {
            // Check if this month's 30th report was sent
            lastThirtieth = today.withDayOfMonth(30);
        } else {
            // Check last month's 30th
            LocalDate lastMonth = today.minusMonths(1);
            // Handle months with fewer than 30 days
            int lastDayOfPrevMonth = lastMonth.lengthOfMonth();
            if (lastDayOfPrevMonth >= 30) {
                lastThirtieth = lastMonth.withDayOfMonth(30);
            } else {
                // Previous month had fewer than 30 days, use last day
                lastThirtieth = lastMonth.withDayOfMonth(lastDayOfPrevMonth);
            }
        }

        List<FinOpsReportSchedule> monthlySchedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.MONTHLY);

        if (monthlySchedules.isEmpty()) {
            logger.info("No active MONTHLY schedules found.");
            return;
        }

        final LocalDate thirtiethToCheck = lastThirtieth;
        boolean anyMissed = monthlySchedules.stream().anyMatch(schedule -> {
            LocalDateTime lastSent = schedule.getLastSent();
            // Missed if never sent OR last sent before the most recent 30th
            return lastSent == null || lastSent.toLocalDate().isBefore(thirtiethToCheck);
        });

        if (anyMissed) {
            logger.info(">>> DETECTED MISSED MONTHLY REPORTS (for {}) - Triggering recovery execution...", lastThirtieth);
            runMonthlyReports();
        } else {
            logger.info("All MONTHLY reports for {} have already been sent.", lastThirtieth);
        }
    }

    @Transactional
    @Scheduled(cron = "0 30 12 * * ?") // 6:00 PM IST daily
    public void runDailyReports() {
        logger.info("Running DAILY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.DAILY);

        LocalDate reportDate = LocalDate.now().minusDays(1); // Report for yesterday
        String dateRange = reportDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Daily", reportDate, reportDate, dateRange, true);
    }

    @Transactional
    @Scheduled(cron = "0 30 12 ? * FRI") // 6:00 PM IST every Friday
    public void runWeeklyReports() {
        logger.info("Running WEEKLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.WEEKLY);

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(6); // Past 7 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to "
                + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Weekly", startDate, endDate, dateRange, false);
    }

    @Transactional
    @Scheduled(cron = "0 30 12 30 * ?") // 6:00 PM IST on the 30th
    public void runMonthlyReports() {
        logger.info("Running MONTHLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository
                .findAllActiveByFrequencyWithDetails(FinOpsReportSchedule.Frequency.MONTHLY);

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(29); // Past 30 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to "
                + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Monthly", startDate, endDate, dateRange, false);
    }

    private void processSchedules(List<FinOpsReportSchedule> schedules, String frequency, LocalDate startDate,
            LocalDate endDate, String dateRange, boolean forceRefresh) {
        if (schedules.isEmpty()) {
            logger.info("No active {} schedules found.", frequency);
            return;
        }

        // Group schedules by CloudAccount to avoid redundant data fetching
        Map<CloudAccount, List<FinOpsReportSchedule>> schedulesByAccount = schedules.stream()
                .collect(Collectors.groupingBy(FinOpsReportSchedule::getCloudAccount));

        logger.info("Found {} {} schedules to process across {} unique accounts for date range {}",
                schedules.size(), frequency, schedulesByAccount.size(), dateRange);

        // Process schedules for each account
        for (Map.Entry<CloudAccount, List<FinOpsReportSchedule>> entry : schedulesByAccount.entrySet()) {
            CloudAccount account = entry.getKey();
            List<FinOpsReportSchedule> accountSchedules = entry.getValue();

            String accountId = account.getProviderAccountId();
            String accountName = account.getAccountName();

            try {
                // --- STEP 1: Fetch data ONCE for the account ---
                List<DetailedCostDto> reportData;
                if ("AWS".equals(account.getProvider())) {
                    logger.info("Fetching AWS cost data for account {} from {} to {} (forceRefresh={})", accountId, startDate, endDate, forceRefresh);
                    reportData = costService.getCostBreakdownByServiceAndRegion(accountId, startDate, endDate, forceRefresh)
                            .join();
                } else if ("GCP".equals(account.getProvider())) {
                    logger.info("Fetching GCP cost data for account {} from {} to {}", accountId, startDate, endDate);
                    reportData = gcpCostService.getCostBreakdownByServiceAndRegionSync(accountId, startDate, endDate);
                } else {
                    logger.warn("Skipping account {}: Unsupported provider {}", accountId, account.getProvider());
                    continue;
                }

                if (reportData == null || reportData.isEmpty()) {
                    logger.warn("No cost data found for account {} for date range {} to {}. Email will still be sent with empty data message.",
                               accountId, startDate, endDate);
                } else {
                    logger.info("Successfully fetched {} cost records for account {}", reportData.size(), accountId);
                }

                // --- STEP 2: Build email body ONCE ---
                String subject = String.format("Your %s FinOps Report - %s (%s)", frequency, accountName, dateRange);
                String htmlBody = emailBuilder.buildProfessionalReportEmail(reportData, accountName, frequency, dateRange, account.getProvider());
                logger.debug("Generated HTML email body with {} characters", htmlBody != null ? htmlBody.length() : 0);

                // Append disk health section for AWS accounts that have agent data
                if (htmlBody != null && "AWS".equals(account.getProvider())) {
                    try {
                        // Cache is populated by DiskAlertScheduler every 4h; forceRefresh=false returns from cache on hit.
                        // On cache miss this call blocks, consistent with the cost data fetch above.
                        List<Map<String, Object>> allVolumes = ebsDiskRightsizingService.getVolumes(accountId, false);
                        List<Map<String, Object>> flaggedVolumes = allVolumes.stream()
                                .filter(v -> v.containsKey("usedPercent") && v.containsKey("sizeGb"))
                                .filter(v -> {
                                    double up = ((Number) v.get("usedPercent")).doubleValue();
                                    int sz    = ((Number) v.get("sizeGb")).intValue();
                                    return up > 80.0 || (up < 25.0 && sz >= 20);
                                })
                                .collect(Collectors.toList());
                        String diskHtml = emailBuilder.buildDiskHealthSection(flaggedVolumes);
                        if (!diskHtml.isEmpty()) {
                            int bodyCloseIdx = htmlBody.lastIndexOf("</body>");
                            if (bodyCloseIdx >= 0) {
                                htmlBody = htmlBody.substring(0, bodyCloseIdx) + diskHtml + htmlBody.substring(bodyCloseIdx);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("[FinOpsReport] Could not inject disk health section for account {}: {}",
                                accountId, e.getMessage());
                    }
                }

                // --- STEP 3: Send email to unique recipients for this account ---
                // Group schedules by email to avoid sending duplicate emails to the same recipient
                Map<String, List<FinOpsReportSchedule>> schedulesByEmail = accountSchedules.stream()
                        .collect(Collectors.groupingBy(FinOpsReportSchedule::getEmail));

                logger.info("Sending {} reports for account {} to {} unique recipients (from {} schedules)",
                           frequency, accountId, schedulesByEmail.size(), accountSchedules.size());

                for (Map.Entry<String, List<FinOpsReportSchedule>> emailEntry : schedulesByEmail.entrySet()) {
                    String recipientEmail = emailEntry.getKey();
                    List<FinOpsReportSchedule> recipientSchedules = emailEntry.getValue();

                    try {
                        logger.info("Attempting to send email to {} for {} schedule(s)",
                                   recipientEmail, recipientSchedules.size());
                        emailService.sendHtmlEmail(recipientEmail, subject, htmlBody);

                        // Update lastSent for all schedules with this email
                        for (FinOpsReportSchedule schedule : recipientSchedules) {
                            updateScheduleLastSent(schedule.getId());
                        }
                        logger.info("✓ Successfully sent {} report to {} for account {} (Schedule IDs: {})",
                                   frequency, recipientEmail, accountName,
                                   recipientSchedules.stream().map(s -> s.getId().toString()).collect(Collectors.joining(", ")));
                    } catch (Exception emailEx) {
                        logger.error("✗ Failed to send email to {}: {}",
                                    recipientEmail, emailEx.getMessage(), emailEx);
                    }
                }

            } catch (Exception e) {
                logger.error("Failed to process schedules for account {}: {}", accountId, e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void updateScheduleLastSent(Long scheduleId) {
        // This method runs in its own transaction to update the "lastSent" timestamp
        scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
            schedule.setLastSent(LocalDateTime.now());
            scheduleRepository.save(schedule);
        });
    }
}
