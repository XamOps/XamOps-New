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

        // Shared with the Azure path via FinOpsCron so both providers store the same expression
        // for the same frequency. Behaviour is unchanged; the logic simply moved.
        String cronExpression = frequency == FinOpsReportSchedule.Frequency.CUSTOM
                ? FinOpsCron.forCustom(dto)
                : FinOpsCron.forFrequency(frequency.name());

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