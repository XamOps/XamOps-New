package com.xammer.billops.service;

import com.xammer.cloud.domain.CreditRequest;
import com.xammer.cloud.domain.User;
import com.xammer.billops.dto.CreditRequestDto;
import com.xammer.billops.repository.CreditRequestRepository;
import com.xammer.billops.repository.UserRepository;
import org.slf4j.Logger; // ADDED
import org.slf4j.LoggerFactory; // ADDED
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut; // ADDED
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional; // ADDED
import java.util.stream.Collectors;

@Service
public class CreditRequestService {

    // ADDED
    private static final Logger logger = LoggerFactory.getLogger(CreditRequestService.class);

    @Autowired
    private CreditRequestRepository creditRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailService emailService;

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
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

        // Omitted for brevity...

        return convertToDto(savedRequest);
    }

    // --- NEW: Admin List (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "creditRequests", key = "'adminList'")
    public Optional<List<CreditRequestDto>> getCachedAllCreditRequests() {
        logger.debug("Attempting to retrieve CACHED 'adminList' credit requests");
        return Optional.empty(); // Spring AOP replaces this
    }

    // --- MODIFIED: Admin List (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "creditRequests", key = "'adminList'")
    public List<CreditRequestDto> getAllCreditRequestsAndCache() {
        logger.debug("Fetching FRESH 'adminList' credit requests and updating cache");
        return creditRequestRepository.findAllOrderBySubmittedDateDesc().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // --- NEW: User List (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "creditRequests", key = "#userId")
    public Optional<List<CreditRequestDto>> getCachedCreditRequestsByUserId(Long userId) {
        logger.debug("Attempting to retrieve CACHED credit requests for user: {}", userId);
        return Optional.empty(); // Spring AOP replaces this
    }

    // --- MODIFIED: User List (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "creditRequests", key = "#userId")
    public List<CreditRequestDto> getCreditRequestsByUserIdAndCache(Long userId) {
        logger.debug("Fetching FRESH credit requests for user: {} and updating cache", userId);
        return creditRequestRepository.findByUserId(userId).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = "creditRequests", allEntries = true)
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