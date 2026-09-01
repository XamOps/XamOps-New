package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Cryptographic verification that an SNS message really came from AWS.
 *
 * <p>
 * {@code POST /api/metering/sns} is a public endpoint that drives billing state — activate,
 * cancel and entitlement changes for AWS Marketplace customers. Nothing verified that a
 * request to it originated from AWS, so anyone who knew the URL could POST a hand-written
 * envelope and change subscription state for any marketplace customer: grant themselves a
 * paid entitlement, or cancel someone else's.
 *
 * <p>
 * The service's only existing check was that a {@code SubscribeURL} ended in
 * {@code .amazonaws.com}, on the one-time handshake. That is a string comparison on a field
 * the caller supplies, not proof of origin, and it never ran on the {@code Notification}
 * messages that carry the actual billing events.
 *
 * <p>
 * AWS SDK v2 ships no equivalent of v1's {@code SnsMessageManager}, so the documented
 * canonicalisation is implemented here rather than adding a dependency on the v1 SDK.
 *
 * <p>
 * Three things have to hold, and all three matter:
 * <ol>
 *   <li>The signing certificate URL is an AWS SNS host over HTTPS — otherwise an attacker
 *       points it at a certificate they control and signs their own messages.</li>
 *   <li>The signature validates against that certificate's public key.</li>
 *   <li>The canonical string is rebuilt from the envelope in AWS's prescribed field order,
 *       so the signature covers the fields actually acted on.</li>
 * </ol>
 */
@Component
public class SnsSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SnsSignatureVerifier.class);

    /**
     * Only AWS-operated SNS hosts may supply a signing certificate.
     *
     * <p>
     * Anchored at both ends deliberately. A suffix check such as
     * {@code endsWith(".amazonaws.com")} is bypassed by
     * {@code https://sns.evil.amazonaws.com.attacker.net/cert.pem}, and a substring check by
     * putting the expected host in a path or userinfo component. The {@code .cn} suffix
     * covers the China partitions, whose hosts end in {@code amazonaws.com.cn}.
     */
    private static final Pattern AWS_SNS_HOST =
            Pattern.compile("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");

    /** Field order AWS uses to build the signed string for each message type. */
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");

    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    /**
     * Certificates are cached because AWS rotates them rarely and fetching one per message
     * would put a network call on the critical path of every billing event — and turn the
     * endpoint into a way to generate outbound requests at will.
     */
    private final Map<String, PublicKey> certificateCache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Escape hatch for local development, where there is no real SNS to sign messages.
     * Defaults to true so that forgetting to set it cannot silently disable verification in
     * a deployed environment.
     */
    @Value("${xamops.metering.sns.verify-signature:true}")
    private boolean verificationEnabled;

    /**
     * Optional allowlist of topic ARNs. Signature verification proves AWS sent the message
     * but not that it came from <em>our</em> topic — any AWS account can create a topic and
     * have SNS sign its messages with a legitimate certificate. Left empty by default so
     * this change does not break an existing deployment whose topic ARN is not configured;
     * set it in production.
     */
    @Value("${xamops.metering.sns.allowed-topic-arns:}")
    private String allowedTopicArns;

    /**
     * @return true only if the envelope carries a valid AWS signature.
     */
    public boolean isValid(JsonNode envelope, String rawBody) {
        if (!verificationEnabled) {
            log.warn("SNS signature verification is DISABLED — this must not be the case in a deployed environment");
            return true;
        }

        try {
            String type = envelope.path("Type").asText("");
            String signature = envelope.path("Signature").asText(null);
            String certUrl = envelope.path("SigningCertURL").asText(null);
            String signatureVersion = envelope.path("SignatureVersion").asText("1");

            if (signature == null || certUrl == null) {
                log.warn("Rejecting SNS message with missing Signature or SigningCertURL");
                return false;
            }

            if (!isAllowedTopic(envelope.path("TopicArn").asText(""))) {
                return false;
            }

            PublicKey publicKey = publicKeyFor(certUrl);
            if (publicKey == null) {
                return false;
            }

            String canonical = canonicalString(envelope, type);
            if (canonical == null) {
                log.warn("Rejecting SNS message with unsupported Type: {}", type);
                return false;
            }

            // SignatureVersion 1 is SHA1withRSA, 2 is SHA256withRSA. Anything else is not a
            // scheme we know how to check, so it is refused rather than assumed.
            String algorithm = switch (signatureVersion) {
                case "1" -> "SHA1withRSA";
                case "2" -> "SHA256withRSA";
                default -> null;
            };
            if (algorithm == null) {
                log.warn("Rejecting SNS message with unknown SignatureVersion: {}", signatureVersion);
                return false;
            }

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));

            boolean valid = verifier.verify(Base64.getDecoder().decode(signature));
            if (!valid) {
                log.warn("Rejecting SNS message with invalid signature (type={})", type);
            }
            return valid;

        } catch (Exception e) {
            // Fails closed: any error in verification is a rejection, never a pass.
            log.warn("Rejecting SNS message — signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private boolean isAllowedTopic(String topicArn) {
        if (allowedTopicArns == null || allowedTopicArns.isBlank()) {
            return true;
        }
        for (String allowed : allowedTopicArns.split(",")) {
            if (allowed.trim().equals(topicArn)) {
                return true;
            }
        }
        log.warn("Rejecting SNS message from unexpected TopicArn");
        return false;
    }

    private PublicKey publicKeyFor(String certUrl) throws Exception {
        PublicKey cached = certificateCache.get(certUrl);
        if (cached != null) {
            return cached;
        }

        URI uri = URI.create(certUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            log.warn("Rejecting SNS SigningCertURL with non-HTTPS scheme");
            return null;
        }
        if (uri.getHost() == null || !AWS_SNS_HOST.matcher(uri.getHost()).matches()) {
            // The important one. Without it an attacker hosts their own certificate, signs
            // a forged billing event with the matching private key, and it verifies.
            log.warn("Rejecting SNS SigningCertURL with non-AWS host");
            return null;
        }

        HttpResponse<InputStream> response = httpClient.send(
                HttpRequest.newBuilder().uri(uri).GET().timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            log.warn("Could not fetch SNS signing certificate, HTTP {}", response.statusCode());
            return null;
        }

        try (InputStream body = response.body()) {
            X509Certificate certificate = (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(body);
            certificate.checkValidity();
            PublicKey key = certificate.getPublicKey();
            certificateCache.put(certUrl, key);
            return key;
        }
    }

    /**
     * Rebuilds the string AWS signed: each field's name and value, every one followed by a
     * newline, in the prescribed order. Absent fields are skipped — {@code Subject} is
     * optional, and including it as empty would produce a different string and fail.
     */
    private String canonicalString(JsonNode envelope, String type) {
        List<String> fields = switch (type) {
            case "Notification" -> NOTIFICATION_FIELDS;
            case "SubscriptionConfirmation", "UnsubscribeConfirmation" -> CONFIRMATION_FIELDS;
            default -> null;
        };
        if (fields == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            JsonNode value = envelope.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            sb.append(field).append('\n').append(value.asText()).append('\n');
        }
        return sb.toString();
    }
}
