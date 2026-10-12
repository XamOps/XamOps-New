package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.ServiceMetadata;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Mints the {@code k8s-aws-v1.} bearer token an EKS cluster's API server accepts.
 *
 * <h2>What the token is</h2>
 *
 * A SigV4 <strong>query-string presigned</strong> {@code GET} of STS {@code GetCallerIdentity},
 * carrying {@code x-k8s-aws-id: <cluster>} as a signed header, base64url-encoded without padding.
 * EKS's authenticator decodes it and <em>calls that URL</em>; whoever STS says the caller is, is the
 * identity the cluster's access entry and RBAC are then evaluated against. So the only thing that
 * matters here is that the URL is one STS will actually honour.
 *
 * <h2>Why this is built with the SDK's presigner and not by hand</h2>
 *
 * The previous implementation header-signed the request with {@code Aws4Signer.sign} and then moved
 * {@code X-Amz-Algorithm}, {@code -Credential}, {@code -Date} and {@code -SignedHeaders} out of the
 * {@code Authorization} header into the query string. A presigned URL's signature has to be computed
 * over a canonical request that <em>already contains</em> those query parameters and whose signed
 * headers are only {@code host;x-k8s-aws-id}, so the moved signature covered the wrong canonical
 * request. It also signed the <em>global</em> host {@code sts.amazonaws.com} with the cluster's
 * region, emitted a {@code &&} double ampersand before the session token, and base64-encoded with
 * the standard alphabet and padding where the authenticator reads raw base64url.
 *
 * <p>Measured, not inferred: calling the URL it produced returned
 * {@code 403 SignatureDoesNotMatch — "Credential should be scoped to a valid region."} and every
 * cluster answered {@code 401 Unauthorized}. Correcting only the region still returned 403. Both
 * callers of this class ({@code EksClusterAccessProvider.open} and
 * {@code EksService.getKubernetesClientForCustomerCluster}) were therefore unable to reach any
 * cluster's API at all.
 *
 * <p>{@link AwsV4HttpSigner} with {@link AwsV4FamilyHttpSigner.AuthLocation#QUERY_STRING} does the
 * canonicalisation, so there is no hand-rolled string building left to get wrong, and the regional
 * endpoint comes from {@link ServiceMetadata} so non-standard partitions ({@code cn-}, GovCloud)
 * resolve to their own STS host rather than a hard-coded commercial one.
 */
@Service
public class EksTokenGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EksTokenGenerator.class);
    private static final String STS_SERVICE = "sts";
    private static final String CLUSTER_ID_HEADER = "x-k8s-aws-id";
    private static final String TOKEN_PREFIX = "k8s-aws-v1.";
    private static final Duration TOKEN_EXPIRATION = Duration.ofSeconds(60);

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
            String presignedUrl = presignedGetCallerIdentityUrl(
                    credentialsProvider.resolveCredentials(), region, clusterName, Clock.systemUTC());
            return token(presignedUrl);
        } catch (Exception e) {
            logger.error("\u274c Failed to generate token", e);
            throw new RuntimeException("Failed to generate EKS token: " + e.getMessage(), e);
        }
    }

    /**
     * The presigned {@code GetCallerIdentity} URL, exactly as the AWS CLI's {@code eks get-token}
     * builds it.
     *
     * <p>{@code clock} is a parameter so the signature is reproducible in a test; production always
     * passes {@link Clock#systemUTC()}. Package-private for {@code EksTokenGeneratorTest}, which
     * decodes the result and asserts the host, the credential scope, the signed headers and the
     * expiry rather than trusting that the SDK was driven correctly.
     */
    static String presignedGetCallerIdentityUrl(AwsCredentials credentials, String region,
            String clusterName, Clock clock) {
        Region signingRegion = Region.of(region);
        // Regional host, resolved per partition: the global sts.amazonaws.com can only be signed for
        // us-east-1, and signing it for the cluster's region is what STS rejected before.
        String host = ServiceMetadata.of(STS_SERVICE).endpointFor(signingRegion).toString();

        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("https")
                .host(host)
                .encodedPath("/")
                .appendRawQueryParameter("Action", "GetCallerIdentity")
                .appendRawQueryParameter("Version", "2011-06-15")
                // Signed, not merely sent: it is what binds this token to ONE cluster, so a token
                // obtained for cluster A cannot be replayed against cluster B.
                .putHeader(CLUSTER_ID_HEADER, clusterName)
                .build();

        SignedRequest signed = AwsV4HttpSigner.create().sign(builder -> builder
                .identity(credentials)
                .request(request)
                .payload(null)
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, STS_SERVICE)
                .putProperty(AwsV4HttpSigner.REGION_NAME, signingRegion.id())
                .putProperty(AwsV4FamilyHttpSigner.AUTH_LOCATION,
                        AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, TOKEN_EXPIRATION)
                .putProperty(HttpSigner.SIGNING_CLOCK, clock));

        return signed.request().getUri().toString();
    }

    /**
     * {@code k8s-aws-v1.} + <strong>raw base64url</strong> of the URL.
     *
     * <p>Url-safe alphabet and no padding, because the authenticator reads it with Go's
     * {@code base64.RawURLEncoding}, which rejects {@code +}, {@code /} and {@code =}.
     */
    static String token(String presignedUrl) {
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(presignedUrl.getBytes(StandardCharsets.UTF_8));
    }
}
