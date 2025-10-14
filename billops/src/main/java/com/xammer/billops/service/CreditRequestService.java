package com.xammer.billops.service;

import com.xammer.billops.domain.CreditRequest;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
    public CreditRequestDto createCreditRequest(CreditRequestDto creditRequestDto) {
        User user = userRepository.findById(creditRequestDto.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        CreditRequest creditRequest = new CreditRequest();
        // --- START OF FINAL FIX ---
        creditRequest.setAwsAccountId(creditRequestDto.getAwsAccountId()); // Reverted
        // --- END OF FINAL FIX ---
        creditRequest.setExpectedCredits(creditRequestDto.getExpectedCredits());
        creditRequest.setServices(creditRequestDto.getServices());
        creditRequest.setUseCase(creditRequestDto.getUseCase());
        creditRequest.setUser(user);
        creditRequest.setStatus("submitted");
        creditRequest.setSubmittedDate(new Date());

        CreditRequest savedRequest = creditRequestRepository.save(creditRequest);

        // Omitted for brevity...

        return convertToDto(savedRequest);
    }

    @Transactional(readOnly = true)
    public List<CreditRequestDto> getAllCreditRequests() {
        return creditRequestRepository.findAllOrderBySubmittedDateDesc().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CreditRequestDto> getCreditRequestsByUserId(Long userId) {
        return creditRequestRepository.findByUserId(userId).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
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
        // --- START OF FINAL FIX ---
        dto.setAwsAccountId(creditRequest.getAwsAccountId()); // Reverted
        // --- END OF FINAL FIX ---
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