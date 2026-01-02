package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
// ✅ FIX: Correct Import for newer AWS SDK v2
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.TreeMap;

@Service
public class EksTokenGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EksTokenGenerator.class);
    private static final String STS_HOST = "sts.amazonaws.com";
    private static final String STS_SERVICE = "sts";
    private static final String TOKEN_PREFIX = "k8s-aws-v1.";
    private static final long TOKEN_EXPIRATION_SECONDS = 60L;
    private static final String AWS_ALGORITHM = "AWS4-HMAC-SHA256";

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private AwsClientProvider awsClientProvider;

    public String generateTokenForCluster(String accountId, String clusterName, String region) {
        logger.info("🔐 [EksTokenGenerator] Generating token for cluster: {} in account: {} region: {}",
                clusterName, accountId, region);

        try {
            CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId)
                    .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));

            AwsCredentialsProvider credentialsProvider = awsClientProvider.getCredentialsProvider(account);
            String presignedUrl = createPresignedGetCallerIdentityUrl(credentialsProvider, region, clusterName);

            String base64Encoded = Base64.getEncoder()
                    .encodeToString(presignedUrl.getBytes(StandardCharsets.UTF_8));
            // Remove any trailing newlines which base64 sometimes adds
            base64Encoded = base64Encoded.replaceAll("\n", "").replaceAll("\r", "");

            return TOKEN_PREFIX + base64Encoded;
        } catch (Exception e) {
            logger.error("❌ Failed to generate token", e);
            throw new RuntimeException("Failed to generate EKS token: " + e.getMessage(), e);
        }
    }

    private String createPresignedGetCallerIdentityUrl(AwsCredentialsProvider credentialsProvider, String region,
            String clusterName) throws Exception {
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("https")
                .host(STS_HOST)
                .encodedPath("/")
                .appendRawQueryParameter("Action", "GetCallerIdentity")
                .appendRawQueryParameter("Version", "2011-06-15")
                // ✅ Presign parameters added to request object before signing
                .appendRawQueryParameter("X-Amz-Expires", String.valueOf(TOKEN_EXPIRATION_SECONDS));

        // ✅ Cluster ID header is required by EKS
        requestBuilder.putHeader("x-k8s-aws-id", clusterName);

        SdkHttpFullRequest requestToSign = requestBuilder.build();
        AwsCredentials credentials = credentialsProvider.resolveCredentials();

        // ✅ FIX: Use Aws4SignerParams
        Aws4SignerParams signerParams = Aws4SignerParams.builder()
                .awsCredentials(credentials)
                .signingName(STS_SERVICE)
                .signingRegion(Region.of(region))
                .build();

        Aws4Signer signer = Aws4Signer.create();
        SdkHttpFullRequest signedRequest = signer.sign(requestToSign, signerParams);

        return reconstructPresignedUrlFromSignedRequest(signedRequest);
    }

    private String reconstructPresignedUrlFromSignedRequest(SdkHttpFullRequest signedRequest) throws Exception {
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("https://").append(signedRequest.host());
        urlBuilder.append(signedRequest.encodedPath() == null ? "/" : signedRequest.encodedPath());
        urlBuilder.append("?");

        boolean firstParam = true;

        // ✅ FIX: Handle Map<String, List<String>> correctly
        if (signedRequest.rawQueryParameters() != null) {
            for (String key : signedRequest.rawQueryParameters().keySet()) {
                List<String> values = signedRequest.rawQueryParameters().get(key);
                if (values != null && !values.isEmpty()) {
                    // AWS V4 signing usually takes the first value for standard params
                    String value = values.get(0);
                    if (!firstParam)
                        urlBuilder.append("&");
                    urlBuilder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                            .append("=")
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                    firstParam = false;
                }
            }
        }

        // Add Signature/Credential from Auth Header if not in query params
        // (Typically AWS4Signer puts them in header, we move to query for presigned URL
        // style)
        String authHeader = signedRequest.firstMatchingHeader("Authorization").orElse(null);
        if (authHeader != null) {
            TreeMap<String, String> authParams = parseAuthorizationHeader(authHeader);
            for (String key : authParams.keySet()) {
                if (!firstParam)
                    urlBuilder.append("&");
                urlBuilder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(authParams.get(key), StandardCharsets.UTF_8));
                firstParam = false;
            }
        }

        // Add X-Amz-Date/Security-Token if in headers
        String dateHeader = signedRequest.firstMatchingHeader("X-Amz-Date").orElse(null);
        if (dateHeader != null) {
            if (!firstParam)
                urlBuilder.append("&");
            urlBuilder.append("X-Amz-Date=").append(URLEncoder.encode(dateHeader, StandardCharsets.UTF_8));
        }

        String tokenHeader = signedRequest.firstMatchingHeader("X-Amz-Security-Token").orElse(null);
        if (tokenHeader != null) {
            if (!firstParam)
                urlBuilder.append("&");
            urlBuilder.append("&X-Amz-Security-Token=").append(URLEncoder.encode(tokenHeader, StandardCharsets.UTF_8));
        }

        return urlBuilder.toString();
    }

    private TreeMap<String, String> parseAuthorizationHeader(String authHeader) {
        TreeMap<String, String> params = new TreeMap<>();
        String algorithm = authHeader.substring(0, authHeader.indexOf(" "));
        params.put("X-Amz-Algorithm", algorithm);
        String credentialsSection = authHeader.substring(algorithm.length() + 1);
        String[] parts = credentialsSection.split(",\\s*");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("Credential="))
                params.put("X-Amz-Credential", part.substring(11));
            else if (part.startsWith("SignedHeaders="))
                params.put("X-Amz-SignedHeaders", part.substring(14));
            else if (part.startsWith("Signature="))
                params.put("X-Amz-Signature", part.substring(10));
        }
        return params;
    }
}