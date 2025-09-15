package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.VerifyAccountRequest;
import com.xammer.billops.repository.CloudAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class AwsAccountService {

    private final CloudAccountRepository cloudAccountRepository;
    private final String cloudFormationTemplateUrl;

    public AwsAccountService(CloudAccountRepository cloudAccountRepository,
                             @Value("${aws.cloudformation.template.url}") String cloudFormationTemplateUrl) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudFormationTemplateUrl = cloudFormationTemplateUrl;
    }

    @Transactional
    public String generateCloudFormationUrl(String accountName, Client client) {
        String externalId = UUID.randomUUID().toString();
        CloudAccount account = new CloudAccount(accountName, externalId, "read-only", client);
        account.setProvider("AWS");
        account.setStatus("PENDING");
        cloudAccountRepository.save(account);

        String stackName = "BillOps-" + accountName.replaceAll("[^a-zA-Z0-9-]", "-");

        try {
            String encodedStackName = URLEncoder.encode(stackName, StandardCharsets.UTF_8);
            String encodedTemplateUrl = URLEncoder.encode(cloudFormationTemplateUrl, StandardCharsets.UTF_8);
            String encodedExternalId = URLEncoder.encode(account.getExternalId(), StandardCharsets.UTF_8);

            return String.format(
                    "https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_ExternalId=%s",
                    encodedTemplateUrl,
                    encodedStackName,
                    encodedExternalId
            );
        } catch (Exception e) {
            throw new RuntimeException("Error generating CloudFormation URL", e);
        }
    }

    @Transactional
    public void verifyAccount(VerifyAccountRequest request) {
        CloudAccount account = cloudAccountRepository.findById(request.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Cloud account not found with id: " + request.getAccountId()));
        account.setStatus("VERIFIED");
        account.setAwsAccountId(request.getAwsAccountId());
        account.setRoleArn(String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName()));
        account.setExternalId(request.getExternalId());
        cloudAccountRepository.save(account);
    }
}