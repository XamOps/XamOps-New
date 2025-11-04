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
import java.time.format.DateTimeFormatter; 
import java.util.List;
import java.util.Map; // <-- NEW IMPORT
import java.util.stream.Collectors; // <-- NEW IMPORT

@Service
public class FinOpsReportSchedulingService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsReportSchedulingService.class);

    private final FinOpsReportScheduleRepository scheduleRepository;
    private final CostService costService; 
    private final GcpCostService gcpCostService; 
    private final EmailService emailService;
    private final FinOpsReportEmailBuilder emailBuilder;

    public FinOpsReportSchedulingService(FinOpsReportScheduleRepository scheduleRepository,
                                       CostService costService, 
                                       GcpCostService gcpCostService, 
                                       EmailService emailService,
                                       FinOpsReportEmailBuilder emailBuilder) {
        this.scheduleRepository = scheduleRepository;
        this.costService = costService; 
        this.gcpCostService = gcpCostService; 
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
    }

    @Transactional(readOnly = true) 
    @Scheduled(cron = "0 30 12 * * ?") // 6:00 PM IST daily
    public void runDailyReports() {
        logger.info("Running DAILY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findAllActiveByFrequencyWithDetails("DAILY");
        
        LocalDate reportDate = LocalDate.now().minusDays(1); // Report for yesterday
        String dateRange = reportDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        
        processSchedules(schedules, "Daily", reportDate, reportDate, dateRange);
    }

    @Transactional(readOnly = true) 
    @Scheduled(cron = "0 30 12 ? * FRI") // 6:00 PM IST every Friday
    public void runWeeklyReports() {
        logger.info("Running WEEKLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findAllActiveByFrequencyWithDetails("WEEKLY");

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(6); // Past 7 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to " + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Weekly", startDate, endDate, dateRange);
    }

    @Transactional(readOnly = true) 
    @Scheduled(cron = "0 30 12 30 * ?") // 6:00 PM IST on the 30th
    public void runMonthlyReports() {
        logger.info("Running MONTHLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findAllActiveByFrequencyWithDetails("MONTHLY");

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(29); // Past 30 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to " + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Monthly", startDate, endDate, dateRange);
    }

    // --- MODIFIED: This method is now much more efficient ---
    private void processSchedules(List<FinOpsReportSchedule> schedules, String frequency, LocalDate startDate, LocalDate endDate, String dateRange) {
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
                    reportData = costService.getCostBreakdownByServiceAndRegion(accountId, startDate, endDate, false).join();
                } else if ("GCP".equals(account.getProvider())) {
                    reportData = gcpCostService.getCostBreakdownByServiceAndRegionSync(accountId, startDate, endDate);
                } else {
                    logger.warn("Skipping account {}: Unsupported provider {}", accountId, account.getProvider());
                    continue;
                }

                // --- STEP 2: Build email body ONCE ---
                String subject = String.format("Your %s FinOps Report - %s (%s)", frequency, accountName, dateRange);
                String htmlBody = emailBuilder.buildSimpleReportEmail(reportData, accountName, frequency, dateRange);

                // --- STEP 3: Send email to all recipients for this account ---
                for (FinOpsReportSchedule schedule : accountSchedules) {
                    try {
                        emailService.sendHtmlEmail(schedule.getEmail(), subject, htmlBody);
                        updateScheduleLastSent(schedule.getId());
                        logger.info("Successfully processed and sent report for schedule ID {} to {}", schedule.getId(), schedule.getEmail());
                    } catch (Exception emailEx) {
                        logger.error("Failed to send email for schedule ID {}: {}", schedule.getId(), emailEx.getMessage());
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

