package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the EKS bearer token, asserted against the URL that comes out.
 *
 * <h2>Why this test exists at all</h2>
 *
 * The previous implementation produced a token that <strong>every</strong> cluster rejected with
 * {@code 401 Unauthorized}, because the presigned URL it built was one STS answered with
 * {@code 403 SignatureDoesNotMatch — "Credential should be scoped to a valid region."}. Nothing
 * caught it: there was no test, and the failure only appears when a real API server validates the
 * token. So the assertions here are deliberately about the <em>wire form</em> — host, credential
 * scope, signed headers, expiry, encoding — which is the part a unit test can actually pin down.
 * The remaining half of the verification is a live call against STS; it is recorded in
 * {@code .agents/plan/item6-evidence.md}, because it needs real credentials and cannot run in CI.
 *
 * <p>The clock is fixed, so the signature is reproducible and the credential scope's date is a
 * constant rather than today.
 */
class EksTokenGeneratorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-10-11T14:38:10Z"), ZoneOffset.UTC);

    private static final AwsSessionCredentials TEMPORARY = AwsSessionCredentials.create(
            "ASIAEXAMPLEEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "FwoGZXIvYXdzEXAMPLE");

    @Test
    @DisplayName("the URL is a query-string presign of GetCallerIdentity on the REGIONAL STS host")
    void theUrlIsAQueryStringPresignOnTheRegionalHost() {
        URI url = URI.create(EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "ap-south-1", "xamops-test-1", FIXED));
        Map<String, List<String>> query = query(url);

        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost())
                .as("the global sts.amazonaws.com can only be signed for us-east-1; signing it for"
                        + " the cluster's region is what STS rejected")
                .isEqualTo("sts.ap-south-1.amazonaws.com");
        assertThat(url.getPath()).isEqualTo("/");

        assertThat(single(query, "Action")).isEqualTo("GetCallerIdentity");
        assertThat(single(query, "Version")).isEqualTo("2011-06-15");
        assertThat(single(query, "X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(single(query, "X-Amz-Date")).isEqualTo("20261011T143810Z");
        assertThat(single(query, "X-Amz-Expires")).isEqualTo("60");
        assertThat(single(query, "X-Amz-Signature")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the credential is scoped to the cluster's region and the sts service")
    void theCredentialScopeNamesTheRegionAndService() {
        Map<String, List<String>> query = query(URI.create(
                EksTokenGenerator.presignedGetCallerIdentityUrl(
                        TEMPORARY, "ap-south-1", "xamops-test-1", FIXED)));

        assertThat(single(query, "X-Amz-Credential"))
                .isEqualTo("ASIAEXAMPLEEXAMPLE/20261011/ap-south-1/sts/aws4_request");
    }

    @Test
    @DisplayName("x-k8s-aws-id is SIGNED, so a token cannot be replayed against another cluster")
    void theClusterIdIsASignedHeader() {
        String forTestOne = EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "ap-south-1", "xamops-test-1", FIXED);
        String forTestTwo = EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "ap-south-1", "xamops-test-2", FIXED);

        assertThat(single(query(URI.create(forTestOne)), "X-Amz-SignedHeaders"))
                .isEqualTo("host;x-k8s-aws-id");
        assertThat(single(query(URI.create(forTestTwo)), "X-Amz-SignedHeaders"))
                .isEqualTo("host;x-k8s-aws-id");
        assertThat(single(query(URI.create(forTestOne)), "X-Amz-Signature"))
                .as("same credentials, same second, different cluster -> different signature, which"
                        + " is the whole point of signing the header")
                .isNotEqualTo(single(query(URI.create(forTestTwo)), "X-Amz-Signature"));
    }

    @Test
    @DisplayName("the session token appears exactly once, and the query has no empty keys")
    void theQueryStringIsWellFormed() {
        String url = EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "ap-south-1", "xamops-test-1", FIXED);
        Map<String, List<String>> query = query(URI.create(url));

        // The previous implementation appended "&&X-Amz-Security-Token=", which produced an empty
        // query key, and could emit the token twice when it was present as both header and param.
        assertThat(query.get("X-Amz-Security-Token")).hasSize(1);
        assertThat(single(query, "X-Amz-Security-Token")).isEqualTo(TEMPORARY.sessionToken());
        assertThat(url).doesNotContain("&&").doesNotContain("?&");
        assertThat(query.keySet()).doesNotContain("");
        for (String key : query.keySet()) {
            assertThat(key).isNotBlank();
        }
    }

    @Test
    @DisplayName("long-term credentials carry no session token at all")
    void longTermCredentialsCarryNoSessionToken() {
        Map<String, List<String>> query = query(URI.create(
                EksTokenGenerator.presignedGetCallerIdentityUrl(
                        AwsBasicCredentials.create("AKIAEXAMPLEEXAMPLE", "secret"),
                        "ap-south-1", "xamops-test-1", FIXED)));

        assertThat(query).doesNotContainKey("X-Amz-Security-Token");
    }

    @Test
    @DisplayName("the token is the prefix plus RAW base64url: url-safe alphabet, no padding")
    void theTokenIsRawBase64Url() {
        // Go's base64.RawURLEncoding, which is what the authenticator uses, rejects '+', '/' and '='.
        String url = EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "ap-south-1", "xamops-test-1", FIXED);

        String token = EksTokenGenerator.token(url);

        assertThat(token).startsWith("k8s-aws-v1.");
        String encoded = token.substring("k8s-aws-v1.".length());
        assertThat(encoded).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(url);
    }

    @Test
    @DisplayName("a non-commercial partition resolves to its own STS host, not the commercial one")
    void nonCommercialPartitionsResolveTheirOwnHost() {
        // Hard-coding ".amazonaws.com" would silently sign a China or GovCloud cluster against a
        // host that does not serve it.
        assertThat(URI.create(EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "cn-north-1", "xamops-test-1", FIXED)).getHost())
                .isEqualTo("sts.cn-north-1.amazonaws.com.cn");
        assertThat(URI.create(EksTokenGenerator.presignedGetCallerIdentityUrl(
                TEMPORARY, "us-gov-west-1", "xamops-test-1", FIXED)).getHost())
                .isEqualTo("sts.us-gov-west-1.amazonaws.com");
    }

    // ---------------------------------------------------------------- helpers

    /** Decodes the raw query string; no SDK help, so the assertions see what the wire sees. */
    private static Map<String, List<String>> query(URI url) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        String raw = url.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return parsed;
        }
        for (String pair : raw.split("&", -1)) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            parsed.computeIfAbsent(decode(key), k -> new ArrayList<>()).add(decode(value));
        }
        return parsed;
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String single(Map<String, List<String>> query, String key) {
        assertThat(query).containsKey(key);
        assertThat(query.get(key)).as("%s must appear exactly once", key).hasSize(1);
        return query.get(key).get(0);
    }
}
