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
    private final EmailService emailService; // <-- ADDED THIS

    // MODIFIED CONSTRUCTOR
    public FinOpsReportScheduleService(FinOpsReportScheduleRepository scheduleRepository,
                                     CloudAccountRepository cloudAccountRepository,
                                     UserRepository userRepository,
                                     EmailService emailService) { // <-- ADDED THIS
        this.scheduleRepository = scheduleRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.emailService = emailService; // <-- ADDED THIS
    }

    @Transactional
    public FinOpsReportScheduleDto createSchedule(FinOpsReportScheduleDto dto, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(dto.getCloudAccountId())
                .orElseThrow(() -> new RuntimeException("Cloud account not found: " + dto.getCloudAccountId()));

        String cron = convertFrequencyToCron(dto.getFrequency());

        FinOpsReportSchedule schedule = new FinOpsReportSchedule(
                dto.getEmail(),
                dto.getFrequency(),
                cron,
                user,
                account
        );
        
        schedule.setActive(true);
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
                schedule.getFrequency()
            );
            // Use the simple sendEmail for this text-based confirmation
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

    private String convertFrequencyToCron(String frequency) {
        switch (frequency) {
            case "DAILY":
                return "0 0 18 * * ?"; // 6 PM daily
            case "WEEKLY":
                return "0 0 18 ? * FRI"; // 6 PM every Friday
            case "MONTHLY":
                return "0 0 18 30 * ?"; // 6 PM on the 30th of the month
            default:
                throw new IllegalArgumentException("Invalid frequency: " + frequency);
        }
    }

    private FinOpsReportScheduleDto mapToDto(FinOpsReportSchedule schedule) {
        return new FinOpsReportScheduleDto(
                schedule.getId(),
                schedule.getCloudAccount().getProviderAccountId(),
                schedule.getCloudAccount().getAccountName(),
                schedule.getEmail(),
                schedule.getFrequency(),
                schedule.isActive()
        );
    }
}