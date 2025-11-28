// package com.xammer.billops.config;

// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
// import software.amazon.awssdk.regions.Region;
// import software.amazon.awssdk.services.s3.S3Client;
// import software.amazon.awssdk.services.s3.presigner.S3Presigner;
// import software.amazon.awssdk.services.sts.StsClient; // Import added

// @Configuration
// public class S3Config {

//     @Value("${app.s3.region:ap-south-1}")
//     private String s3Region;

//     @Bean
//     public S3Client s3Client() {
//         return S3Client.builder()
//                 .region(Region.of(s3Region))
//                 .credentialsProvider(DefaultCredentialsProvider.create())
//                 .build();
//     }

//     @Bean
//     public S3Presigner s3Presigner() {
//         return S3Presigner.builder()
//                 .region(Region.of(s3Region))
//                 .credentialsProvider(DefaultCredentialsProvider.create())
//                 .build();
//     }

//     // --- Added StsClient Bean ---
//     @Bean
//     public StsClient stsClient() {
//         return StsClient.builder()
//                 .region(Region.of(s3Region)) 
//                 .credentialsProvider(DefaultCredentialsProvider.create())
//                 .build();
//     }
// }