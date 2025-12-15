package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AgreementDto;
import com.xammer.billops.repository.AgreementRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.Agreement;
import com.xammer.cloud.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgreementService {

    private static final Logger logger = LoggerFactory.getLogger(AgreementService.class);

    private final AgreementRepository agreementRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final FileStorageService fileStorageService;
    private final UserRepository userRepository;

    public AgreementService(AgreementRepository agreementRepository,
            CloudAccountRepository cloudAccountRepository,
            FileStorageService fileStorageService,
            UserRepository userRepository) {
        this.agreementRepository = agreementRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.fileStorageService = fileStorageService;
        this.userRepository = userRepository;
    }

    /**
     * Helper to resolve CloudAccount by String Identifier (AWS ID, GCP Project, or
     * Azure Sub ID).
     */
    private CloudAccount resolveAccount(String accountIdentifier) {
        return cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(
                accountIdentifier, accountIdentifier, accountIdentifier).orElseThrow(
                        () -> new RuntimeException("Cloud Account not found for identifier: " + accountIdentifier));
    }

    @Transactional
    public AgreementDto uploadAgreement(String accountIdentifier, MultipartFile file, Long userId) {
        // Resolve the correct internal account entity using the string ID
        CloudAccount account = resolveAccount(accountIdentifier);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Upload to S3
        String key = fileStorageService.uploadFile(file);

        // Save to DB
        Agreement agreement = new Agreement();
        agreement.setFileName(file.getOriginalFilename());
        agreement.setS3Key(key);
        agreement.setContentType(file.getContentType());
        agreement.setStatus("DRAFT");
        agreement.setUploadedAt(LocalDateTime.now());
        agreement.setCloudAccount(account);
        agreement.setUploadedBy(user);

        Agreement savedAgreement = agreementRepository.save(agreement);

        String url = fileStorageService.generatePresignedUrl(savedAgreement.getS3Key());
        return AgreementDto.fromEntity(savedAgreement, url);
    }

    @Transactional
    public AgreementDto finalizeAgreement(Long agreementId) {
        Agreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("Agreement not found"));

        if (!"DRAFT".equals(agreement.getStatus())) {
            throw new IllegalStateException("Agreement is already finalized or invalid state.");
        }

        agreement.setStatus("FINALIZED");
        agreement.setFinalizedAt(LocalDateTime.now());
        Agreement saved = agreementRepository.save(agreement);

        String url = fileStorageService.generatePresignedUrl(saved.getS3Key());
        return AgreementDto.fromEntity(saved, url);
    }

    @Transactional(readOnly = true)
    public List<AgreementDto> getAgreementsForAccount(String accountIdentifier, boolean isAdmin) {
        // Resolve the correct internal account entity using the string ID
        CloudAccount account = resolveAccount(accountIdentifier);
        List<Agreement> agreements;

        if (isAdmin) {
            agreements = agreementRepository.findByCloudAccountId(account.getId());
        } else {
            agreements = agreementRepository.findByCloudAccountIdAndStatus(account.getId(), "FINALIZED");
        }

        return agreements.stream()
                .map(agreement -> {
                    String url = fileStorageService.generatePresignedUrl(agreement.getS3Key());
                    return AgreementDto.fromEntity(agreement, url);
                })
                .sorted((a, b) -> b.getUploadedAt().compareTo(a.getUploadedAt()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAgreement(Long agreementId) {
        Agreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("Agreement not found"));
        agreementRepository.delete(agreement);
    }
}