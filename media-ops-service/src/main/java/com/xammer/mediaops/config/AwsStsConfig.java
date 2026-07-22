package com.xammer.mediaops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * STS client used to assume a customer's onboarding IAM role so transcoded output
 * can be written to a bucket in that customer's own AWS account. The media-ops host
 * identity (IAM role / instance profile) must be permitted to sts:AssumeRole the
 * customer roles — the same trust XamOps already relies on to read those accounts.
 */
@Configuration
public class AwsStsConfig {

    @Value("${mediaops.aws.sts-region:ap-south-1}")
    private String stsRegion;

    @Bean
    public StsClient stsClient() {
        return StsClient.builder()
                .region(Region.of(stsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
