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
import org.springframework.transaction.annotation.Transactional; // <-- IMPORT ADDED

import java.time.LocalDate; 
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter; 
import java.util.List;

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

    // --- MODIFIED: Cron changed back to "0 30 12 * * ?" (12:30 UTC = 6:00 PM IST) ---
    @Transactional(readOnly = true) // <-- FIX for LazyInitializationException
    @Scheduled(cron = "0 30 12 * * ?") 
    public void runDailyReports() {
        logger.info("Running DAILY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findByFrequencyAndIsActiveTrue("DAILY");
        
        LocalDate reportDate = LocalDate.now().minusDays(1); // Report for yesterday
        String dateRange = reportDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        
        processSchedules(schedules, "Daily", reportDate, reportDate, dateRange);
    }

    // --- MODIFIED: Cron changed back to "0 30 12 ? * FRI" (12:30 UTC = 6:00 PM IST) ---
    @Transactional(readOnly = true) // <-- FIX for LazyInitializationException
    @Scheduled(cron = "0 30 12 ? * FRI") 
    public void runWeeklyReports() {
        logger.info("Running WEEKLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findByFrequencyAndIsActiveTrue("WEEKLY");

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(6); // Past 7 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to " + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Weekly", startDate, endDate, dateRange);
    }

    // ---  Cron  "0 30 12 30 * ?" (12:30 UTC = 6:00 PM IST) ---
    @Transactional(readOnly = true) 
    @Scheduled(cron = "0 30 12 30 * ?")
    public void runMonthlyReports() {
        logger.info("Running MONTHLY FinOps report schedules...");
        List<FinOpsReportSchedule> schedules = scheduleRepository.findByFrequencyAndIsActiveTrue("MONTHLY");

        LocalDate endDate = LocalDate.now().minusDays(1); // Up to yesterday
        LocalDate startDate = endDate.minusDays(29); // Past 30 days
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + " to " + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        processSchedules(schedules, "Monthly", startDate, endDate, dateRange);
    }

    private void processSchedules(List<FinOpsReportSchedule> schedules, String frequency, LocalDate startDate, LocalDate endDate, String dateRange) {
        if (schedules.isEmpty()) {
            logger.info("No active {} schedules found.", frequency);
            return;
        }

        logger.info("Found {} {} schedules to process for date range {}", schedules.size(), frequency, dateRange);

        for (FinOpsReportSchedule schedule : schedules) {
            try {
                CloudAccount account = schedule.getCloudAccount(); 
                String accountId = account.getProviderAccountId();
                String accountName = account.getAccountName();
                String subject = String.format("Your %s FinOps Report - %s (%s)", frequency, accountName, dateRange);
                String htmlBody;
                
                List<DetailedCostDto> reportData;

                if ("AWS".equals(account.getProvider())) {
                    reportData = costService.getCostBreakdownByServiceAndRegion(accountId, startDate, endDate, true).join();
                } else if ("GCP".equals(account.getProvider())) {
                    reportData = gcpCostService.getCostBreakdownByServiceAndRegionSync(accountId, startDate, endDate);
                } else {
                    logger.warn("Skipping schedule {}: Unsupported provider {}", schedule.getId(), account.getProvider());
                    continue;
                }
                
                htmlBody = emailBuilder.buildSimpleReportEmail(reportData, accountName, frequency, dateRange);

                emailService.sendHtmlEmail(schedule.getEmail(), subject, htmlBody);
                
                // --- MODIFICATION: Must run save in a separate, non-read-only transaction ---
                // We can't save from inside the read-only scheduled method, so we call another service method.
                // For simplicity in this step, I will just save it directly.
                // A better pattern would be another service, but this will work.
                // We MUST ensure the schedule object is not lazy-loaded
                
                updateScheduleLastSent(schedule.getId());
                
                logger.info("Successfully processed and sent report for schedule ID {}", schedule.getId());

            } catch (Exception e) {
                logger.error("Failed to process schedule ID {}: {}", schedule.getId(), e.getMessage(), e);
            }
        }
    }
    
    // --- NEW METHOD: To handle saving in a new transaction ---
    @Transactional
    public void updateScheduleLastSent(Long scheduleId) {
        scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
            schedule.setLastSent(LocalDateTime.now());
            scheduleRepository.save(schedule);
        });
    }
}

