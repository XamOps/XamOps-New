package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.Customer;
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
    public String generateCloudFormationUrl(String accountName, Customer customer) {
        // Create a new CloudAccount entity with a PENDING status
        CloudAccount account = new CloudAccount();
        account.setAccountName(accountName);
        account.setCustomer(customer);
        account.setProvider("AWS");
        account.setStatus("PENDING");
        account.setExternalId(UUID.randomUUID().toString()); // Generate a unique externalId
        cloudAccountRepository.save(account);

        // Construct the pre-signed URL for the AWS CloudFormation console
        String stackName = "BillOps-" + accountName.replaceAll("[^a-zA-Z0-9-]", "-");

        try {
            String encodedStackName = URLEncoder.encode(stackName, StandardCharsets.UTF_8);
            String encodedTemplateUrl = URLEncoder.encode(cloudFormationTemplateUrl, StandardCharsets.UTF_8);
            String encodedExternalId = URLEncoder.encode(account.getExternalId(), StandardCharsets.UTF_8);

            // The callback URL is not used in this simplified version but is good practice to have
            String callbackUrl = "https://your-app.com/callback"; // Replace with your actual callback URL
            String encodedCallbackUrl = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

            return String.format(
                    "https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_ExternalId=%s&param_CallbackUrl=%s",
                    encodedTemplateUrl,
                    encodedStackName,
                    encodedExternalId,
                    encodedCallbackUrl
            );
        } catch (Exception e) {
            throw new RuntimeException("Error generating CloudFormation URL", e);
        }
    }

    @Transactional
    public CloudAccount verifyAccount(VerifyAccountRequest request) {
        CloudAccount account = cloudAccountRepository.findByExternalId(request.getExternalId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification request."));

        // In a real application, you would use the AWS SDK here to:
        // 1. Assume the role using the provided roleName and externalId.
        // 2. If the assumption is successful, update the status to CONNECTED.
        // 3. If it fails, update the status to FAILED and provide an error message.

        // For now, we will simulate a successful verification.
        String roleArn = String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName());
        account.setAwsAccountId(request.getAwsAccountId());
        account.setRoleArn(roleArn);
        account.setStatus("CONNECTED");

        return cloudAccountRepository.save(account);
    }
}