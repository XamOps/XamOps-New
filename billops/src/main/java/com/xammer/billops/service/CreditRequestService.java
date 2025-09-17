package com.xammer.billops.service;

import com.xammer.billops.domain.CreditRequest;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CreditRequestService {

    @Autowired
    private CreditRequestRepository creditRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Transactional
    @CacheEvict(value = {"allCreditRequests", "creditRequestsByUser"}, allEntries = true)
    public CreditRequestDto createCreditRequest(CreditRequestDto creditRequestDto) {
        User user = userRepository.findById(creditRequestDto.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        CreditRequest creditRequest = new CreditRequest();
        creditRequest.setAwsAccountId(creditRequestDto.getAwsAccountId());
        creditRequest.setExpectedCredits(creditRequestDto.getExpectedCredits());
        creditRequest.setServices(creditRequestDto.getServices());
        creditRequest.setUseCase(creditRequestDto.getUseCase());
        creditRequest.setUser(user);
        creditRequest.setStatus("submitted");
        creditRequest.setSubmittedDate(new Date());

        CreditRequest savedRequest = creditRequestRepository.save(creditRequest);

        // Send email notification
        String emailSubject = "New Credit Request Submitted: " + savedRequest.getAwsAccountId();
        String emailText = "A new credit request has been submitted with the following details:\n\n" +
                "AWS Account ID: " + savedRequest.getAwsAccountId() + "\n" +
                "Expected Credits: " + savedRequest.getExpectedCredits() + "\n" +
                "Services: " + savedRequest.getServices() + "\n" +
                "Use Case: " + savedRequest.getUseCase() + "\n" +
                "User: " + user.getUsername();
        emailService.sendSimpleMessage("aditya@xammer.in", emailSubject, emailText);

        return convertToDto(savedRequest);
    }

    @Transactional(readOnly = true)
    @Cacheable("allCreditRequests")
    public List<CreditRequestDto> getAllCreditRequests() {
        return creditRequestRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "creditRequestsByUser", key = "#userId")
    public List<CreditRequestDto> getCreditRequestsByUserId(Long userId) {
        return creditRequestRepository.findByUserId(userId).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = {"allCreditRequests", "creditRequestsByUser"}, allEntries = true)
    public CreditRequestDto updateRequestStatus(Long id, String status) {
        CreditRequest creditRequest = creditRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Credit Request not found"));
        creditRequest.setStatus(status);
        CreditRequest updatedRequest = creditRequestRepository.save(creditRequest);
        return convertToDto(updatedRequest);
    }

    private CreditRequestDto convertToDto(CreditRequest creditRequest) {
        CreditRequestDto dto = new CreditRequestDto();
        dto.setId(creditRequest.getId());
        dto.setAwsAccountId(creditRequest.getAwsAccountId());
        dto.setExpectedCredits(creditRequest.getExpectedCredits());
        dto.setServices(creditRequest.getServices());
        dto.setUseCase(creditRequest.getUseCase());
        dto.setStatus(creditRequest.getStatus());
        dto.setSubmittedDate(creditRequest.getSubmittedDate());
        if (creditRequest.getUser() != null) {
            dto.setUserId(creditRequest.getUser().getId());
        }
        return dto;
    }
}