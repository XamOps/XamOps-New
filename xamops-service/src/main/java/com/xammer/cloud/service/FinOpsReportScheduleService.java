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

    public FinOpsReportScheduleService(FinOpsReportScheduleRepository scheduleRepository,
                                     CloudAccountRepository cloudAccountRepository,
                                     UserRepository userRepository,
                                     EmailService emailService) {
        this.scheduleRepository = scheduleRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Transactional
    public FinOpsReportScheduleDto createSchedule(FinOpsReportScheduleDto dto, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(dto.getCloudAccountId())
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + dto.getCloudAccountId()));

        FinOpsReportSchedule.Frequency frequency = FinOpsReportSchedule.Frequency.valueOf(dto.getFrequency().toUpperCase());
        
        // --- FIX: Generate cron expression for the given frequency ---
        String cronExpression = convertFrequencyToCron(frequency.name());

        FinOpsReportSchedule schedule = new FinOpsReportSchedule();
        schedule.setEmail(dto.getEmail());
        schedule.setFrequency(frequency);
        schedule.setUser(user);
        schedule.setCloudAccount(account);
        schedule.setActive(true);
        
        // --- FIX: Set the non-null cron_expression field ---
        schedule.setCronExpression(cronExpression);
        
        schedule = scheduleRepository.save(schedule);
        logger.info("Created new FinOps report schedule ID {} for account {} with frequency {}", 
                schedule.getId(), account.getAccountName(), schedule.getFrequency());

        // --- START: ADD ACKNOWLEDGEMENT EMAIL LOGIC ---
        try {
            String subject = "XamOps Report Schedule Confirmed";
            String body = String.format(
                "Hello,\n\n" +
                "This is an acknowledgement that you have successfully set up a new FinOps report schedule.\n\n" +
                "  Account: %s (%s)\n" +
                "  Recipient: %s\n" +
                "  Frequency: %s\n\n" +
                "You will receive your first report at the next scheduled time.\n\n" +
                "Thank you,\n" +
                "The XamOps Team",
                account.getAccountName(),
                account.getProviderAccountId(),
                schedule.getEmail(),
                schedule.getFrequency().name() 
            );
            
            emailService.sendEmail(schedule.getEmail(), subject, body); 
            logger.info("Sent schedule acknowledgement email to {}", schedule.getEmail());
        } catch (Exception e) {
            logger.error("Failed to send acknowledgement email for schedule ID {}: {}", schedule.getId(), e.getMessage(), e);
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