package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.FinOpsReportScheduleRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FinOpsReportScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsReportScheduleService.class);

    private final FinOpsReportScheduleRepository scheduleRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FinOpsReportEmailBuilder emailBuilder;

    public FinOpsReportScheduleService(FinOpsReportScheduleRepository scheduleRepository,
            CloudAccountRepository cloudAccountRepository,
            UserRepository userRepository,
            EmailService emailService,
            FinOpsReportEmailBuilder emailBuilder) {
        this.scheduleRepository = scheduleRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
    }

    @Transactional
    public FinOpsReportScheduleDto createSchedule(FinOpsReportScheduleDto dto, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        CloudAccount account = cloudAccountRepository.findByProviderAccountId(dto.getCloudAccountId())
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + dto.getCloudAccountId()));

        FinOpsReportSchedule.Frequency frequency = FinOpsReportSchedule.Frequency
                .valueOf(dto.getFrequency().toUpperCase());

        // Generate cron expression based on frequency type
        String cronExpression;
        if (frequency == FinOpsReportSchedule.Frequency.CUSTOM) {
            // Validate custom time parameters
            validateCustomTimeParams(dto);
            cronExpression = generateCustomCronExpression(dto);
        } else {
            cronExpression = convertFrequencyToCron(frequency.name());
        }

        FinOpsReportSchedule schedule = new FinOpsReportSchedule();
        schedule.setEmail(dto.getEmail());
        schedule.setFrequency(frequency);
        schedule.setUser(user);
        schedule.setCloudAccount(account);
        schedule.setActive(true);
        schedule.setCronExpression(cronExpression);

        schedule = scheduleRepository.save(schedule);
        logger.info("Created new FinOps report schedule ID {} for account {} with frequency {}",
                schedule.getId(), account.getAccountName(), schedule.getFrequency());

        // --- START: ADD ACKNOWLEDGEMENT EMAIL LOGIC ---
        try {
            String subject = "XamOps Report Schedule Confirmed - " + account.getAccountName();
            String htmlBody = emailBuilder.buildScheduleConfirmationEmail(
                    account.getAccountName(),
                    account.getProviderAccountId(),
                    schedule.getEmail(),
                    schedule.getFrequency().name(),
                    account.getProvider());

            emailService.sendHtmlEmail(schedule.getEmail(), subject, htmlBody);
            logger.info("Sent schedule acknowledgement email to {}", schedule.getEmail());
        } catch (Exception e) {
            logger.error("Failed to send acknowledgement email for schedule ID {}: {}", schedule.getId(),
                    e.getMessage(), e);
            // We don't stop the transaction for this, just log the error.
        }
        // --- END: ADD ACKNOWLEDGEMENT EMAIL LOGIC ---

        return mapToDto(schedule);
    }

    @Transactional(readOnly = true)
    public List<FinOpsReportScheduleDto> getSchedulesForUserAndAccount(String username, String accountId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + accountId));

        return scheduleRepository.findByUserIdAndCloudAccountId(user.getId(), account.getId())
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteSchedule(Long scheduleId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        FinOpsReportSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));

        if (!schedule.getUser().getId().equals(user.getId())) {
            // Add admin role check here if needed
            throw new SecurityException("User does not have permission to delete this schedule");
        }

        scheduleRepository.delete(schedule);
        logger.info("Deleted FinOps report schedule ID {}", scheduleId);
    }

    /**
     * Converts a frequency string to a cron expression.
     * Times are set to 12:30 UTC (6:00 PM IST).
     */
    private String convertFrequencyToCron(String frequency) {
        switch (frequency) {
            case "DAILY":
                return "30 12 * * ?"; // 6:00 PM IST daily
            case "WEEKLY":
                return "30 12 ? * FRI"; // 6:00 PM IST every Friday
            case "MONTHLY":
                return "30 12 30 * ?"; // 6:00 PM IST on the 30th of the month
            default:
                throw new IllegalArgumentException("Invalid frequency: " + frequency);
        }
    }

    /**
     * Validates custom time parameters.
     */
    private void validateCustomTimeParams(FinOpsReportScheduleDto dto) {
        if (dto.getCustomHour() == null || dto.getCustomHour() < 0 || dto.getCustomHour() > 23) {
            throw new IllegalArgumentException("Custom hour must be between 0 and 23");
        }
        if (dto.getCustomMinute() == null || dto.getCustomMinute() < 0 || dto.getCustomMinute() > 59) {
            throw new IllegalArgumentException("Custom minute must be between 0 and 59");
        }
        if (dto.getCustomFrequencyType() == null || dto.getCustomFrequencyType().isEmpty()) {
            throw new IllegalArgumentException("Custom frequency type is required");
        }

        String freqType = dto.getCustomFrequencyType().toUpperCase();
        if (freqType.equals("WEEKLY")) {
            if (dto.getCustomDayOfWeek() == null || dto.getCustomDayOfWeek() < 1 || dto.getCustomDayOfWeek() > 7) {
                throw new IllegalArgumentException("Custom day of week must be between 1 (Monday) and 7 (Sunday)");
            }
        } else if (freqType.equals("MONTHLY")) {
            if (dto.getCustomDayOfMonth() == null || dto.getCustomDayOfMonth() < 1 || dto.getCustomDayOfMonth() > 31) {
                throw new IllegalArgumentException("Custom day of month must be between 1 and 31");
            }
        }
    }

    /**
     * Generates a cron expression based on custom time parameters.
     * Cron format: second minute hour day-of-month month day-of-week
     */
    private String generateCustomCronExpression(FinOpsReportScheduleDto dto) {
        int minute = dto.getCustomMinute();
        int hour = dto.getCustomHour();
        String freqType = dto.getCustomFrequencyType().toUpperCase();

        switch (freqType) {
            case "DAILY":
                // Run daily at specified time
                return String.format("0 %d %d * * ?", minute, hour);

            case "WEEKLY":
                // Run weekly on specified day at specified time
                // Convert 1-7 (Mon-Sun) to cron day format (MON-SUN)
                String[] days = { "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" };
                String dayOfWeek = days[dto.getCustomDayOfWeek() - 1];
                return String.format("0 %d %d ? * %s", minute, hour, dayOfWeek);

            case "MONTHLY":
                // Run monthly on specified day at specified time
                int dayOfMonth = dto.getCustomDayOfMonth();
                return String.format("0 %d %d %d * ?", minute, hour, dayOfMonth);

            default:
                throw new IllegalArgumentException("Invalid custom frequency type: " + freqType);
        }
    }

    private FinOpsReportScheduleDto mapToDto(FinOpsReportSchedule schedule) {
        // --- FIX: Use setters as the DTO constructor was not matching ---
        FinOpsReportScheduleDto dto = new FinOpsReportScheduleDto();
        dto.setId(schedule.getId());
        dto.setCloudAccountId(schedule.getCloudAccount().getProviderAccountId());
        dto.setAccountName(schedule.getCloudAccount().getAccountName());
        dto.setEmail(schedule.getEmail());
        dto.setFrequency(schedule.getFrequency().name()); // Convert Enum to String
        dto.setActive(schedule.isActive());
        return dto;
    }
}