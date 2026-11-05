package com.xammer.cloud.xampulse.service.agent;

import com.sun.net.httpserver.HttpServer;
import com.xammer.cloud.xampulse.dto.agent.EnrollRequest;
import com.xammer.cloud.xampulse.dto.agent.StsRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** SSRF guard, nonce binding, ARN parsing and XML hardening of the enrolment identity proof. */
class StsIdentityVerifierTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String REGION = "ap-south-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=ASIAEXAMPLE/20261102/ap-south-1/sts/aws4_request, "
            + "SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-xampulse-nonce, Signature=abcd";

    private static String xml(String account, String arn) {
        return "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
                + "<Arn>" + arn + "</Arn><UserId>AROAEXAMPLE:i-0123456789abcdef0</UserId><Account>" + account + "</Account>"
                + "</GetCallerIdentityResult><ResponseMetadata><RequestId>r</RequestId></ResponseMetadata></GetCallerIdentityResponse>";
    }

    private static final String GOOD_ARN = "arn:aws:sts::123456789012:assumed-role/MyInstanceRole/i-0123456789abcdef0";

    /** Records what the verifier would send to STS. */
    private static final class FakeTransport implements StsTransport {
        final List<URI> uris = new ArrayList<>();
        Map<String, String> headers;
        String body;
        int status = 200;
        String response = xml("123456789012", GOOD_ARN);
        boolean fail;

        @Override
        public Response post(URI uri, Map<String, String> h, String b) throws IOException {
            uris.add(uri);
            headers = h;
            body = b;
            if (fail) {
                throw new IOException("boom");
            }
            return new Response(status, response);
        }
    }

    private static Map<String, List<String>> headers(String nonce, String auth) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        h.put("content-type", List.of("application/x-www-form-urlencoded; charset=utf-8"));
        h.put("host", List.of("sts.ap-south-1.amazonaws.com"));
        h.put("x-amz-date", List.of("20261102T080521Z"));
        h.put("x-amz-security-token", List.of("TOKEN"));
        if (nonce != null) {
            h.put("x-xampulse-nonce", List.of(nonce));
        }
        h.put("authorization", List.of(auth));
        return h;
    }

    private static EnrollRequest request(String url, String method, String body, Map<String, List<String>> h) {
        return new EnrollRequest(NONCE, REGION, new StsRequest(url, method, h, body), "1.0.0", "linux", "amd64", "host");
    }

    private static EnrollRequest good() {
        return request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, AUTH));
    }

    private void rejected(FakeTransport t, EnrollRequest r) {
        assertThrows(StsIdentityVerifier.Rejected.class, () -> new StsIdentityVerifier(t).verify(r));
    }

    @Test
    void acceptsAGoodProofAndDerivesTheInstanceIdFromTheSessionName() {
        FakeTransport t = new FakeTransport();
        StsIdentityVerifier.Identity id = new StsIdentityVerifier(t).verify(good());
        assertEquals("123456789012", id.accountId());
        assertEquals("i-0123456789abcdef0", id.instanceId());
        assertEquals(GOOD_ARN, id.arn());
        assertEquals(List.of(URI.create("https://sts.ap-south-1.amazonaws.com/")), t.uris);
        assertEquals(StsIdentityVerifier.EXPECTED_BODY, t.body);
    }

    @Test
    void theGlobalStsHostIsAllowedToo() {
        FakeTransport t = new FakeTransport();
        new StsIdentityVerifier(t).verify(request("https://sts.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, AUTH)));
        assertEquals("sts.amazonaws.com", t.uris.get(0).getHost());
    }

    @Test
    void onlyTheSignedHeadersTheProofNeedsAreForwarded() {
        FakeTransport t = new FakeTransport();
        Map<String, List<String>> h = headers(NONCE, AUTH);
        h.put("x-evil", List.of("1"));
        h.put("cookie", List.of("a=b"));
        new StsIdentityVerifier(t).verify(request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, h));
        assertEquals(java.util.Set.of("content-type", "x-amz-date", "x-amz-security-token", "authorization", "x-xampulse-nonce"), t.headers.keySet());
    }

    @Test
    void rejectsEveryHostThatIsNotExactlyStsAndNeverCallsOut() {
        String[] urls = {
                "https://sts.amazonaws.com.evil.test/", "https://evil.test/", "http://sts.ap-south-1.amazonaws.com/",
                "https://user@sts.ap-south-1.amazonaws.com/", "https://sts.ap-south-1.amazonaws.com@evil.test/",
                "https://sts.ap-south-1.amazonaws.com:8443/", "https://sts.ap-south-1.amazonaws.com:443/",
                "https://sts.us-east-1.amazonaws.com/", // another region than the one the nonce was issued for
                "https://sts.ap-south-1.amazonaws.com/?Action=Other", "https://sts.ap-south-1.amazonaws.com/other",
                "https://sts.ap-south-1.amazonaws.com/#x", "https://169.254.169.254/latest/meta-data/", "https://localhost/",
                "https://sts.ap-south-1.amazonaws.com.cn/", "https://notsts.ap-south-1.amazonaws.com/", "ftp://sts.amazonaws.com/",
                "//sts.amazonaws.com/", "https://sts..amazonaws.com/", "https://STS.EVIL.test/", "", null, "not a url"};
        for (String u : urls) {
            FakeTransport t = new FakeTransport();
            rejected(t, request(u, "POST", StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, AUTH)));
            assertTrue(t.uris.isEmpty(), "STS must not be called for " + u);
        }
    }

    @Test
    void methodAndBodyAreFixed() {
        for (String m : new String[] {"GET", "PUT", "post", null}) {
            FakeTransport t = new FakeTransport();
            rejected(t, request("https://sts.ap-south-1.amazonaws.com/", m, StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, AUTH)));
            assertTrue(t.uris.isEmpty());
        }
        for (String b : new String[] {"Action=AssumeRole&Version=2011-06-15", StsIdentityVerifier.EXPECTED_BODY + "&x=1", "", null}) {
            FakeTransport t = new FakeTransport();
            rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", b, headers(NONCE, AUTH)));
            assertTrue(t.uris.isEmpty());
        }
    }

    @Test
    void theNonceMustBeASignedHeaderThatMatches() {
        FakeTransport t = new FakeTransport();
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers(null, AUTH)));
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers("ffffffffffffffffffffffffffffffff", AUTH)));
        // Present as a header but not covered by the signature: the proof could be re-bound to another challenge.
        String unsigned = AUTH.replace(";x-xampulse-nonce", "");
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, unsigned)));
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, headers(NONCE, "no signature here")));
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, null));
        assertTrue(t.uris.isEmpty());
    }

    @Test
    void headerValuesWithLineBreaksAreRefused() {
        Map<String, List<String>> h = headers(NONCE, AUTH);
        h.put("x-amz-date", List.of("2026\r\nX-Injected: 1"));
        FakeTransport t = new FakeTransport();
        rejected(t, request("https://sts.ap-south-1.amazonaws.com/", "POST", StsIdentityVerifier.EXPECTED_BODY, h));
        assertTrue(t.uris.isEmpty());
    }

    @Test
    void stsFailuresAreRejections() {
        FakeTransport t = new FakeTransport();
        t.status = 403;
        rejected(t, good());
        t = new FakeTransport();
        t.fail = true;
        rejected(t, good());
        t = new FakeTransport();
        t.status = 302;
        rejected(t, good());
    }

    @Test
    void arnParsingAcceptsOddRoleNamesButOnlyInstanceSessions() {
        String[] ok = {"arn:aws:sts::123456789012:assumed-role/My+Role=,.@_-x/i-0abcdef0",
                "arn:aws:sts::123456789012:assumed-role/a/i-0123456789abcdef0"};
        for (String arn : ok) {
            FakeTransport t = new FakeTransport();
            t.response = xml("123456789012", arn);
            assertTrue(new StsIdentityVerifier(t).verify(good()).instanceId().startsWith("i-"), arn);
        }
        String[] bad = {"arn:aws:sts::123456789012:assumed-role/Role/session-name", "arn:aws:sts::123456789012:assumed-role/Role/i-ZZZZ",
                "arn:aws:iam::123456789012:user/alice", "arn:aws:sts::123456789012:federated-user/bob",
                "arn:aws:sts::123456789012:assumed-role/Role/i-0123456789abcdef0/extra", "arn:aws:sts::123456789012:assumed-role//i-0123456789abcdef0",
                "arn:aws-cn:sts::123456789012:assumed-role/Role/i-0123456789abcdef0", "arn:aws:sts::999999999999:assumed-role/Role/i-0123456789abcdef0"};
        for (String arn : bad) {
            FakeTransport t = new FakeTransport();
            t.response = xml("123456789012", arn);
            rejected(t, good());
        }
    }

    @Test
    void xmlWithEntitiesOrDoctypeIsRejectedWithoutResolvingAnything() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult>"
                + "<Arn>" + GOOD_ARN + "</Arn><Account>&x;</Account></GetCallerIdentityResult></GetCallerIdentityResponse>";
        FakeTransport t = new FakeTransport();
        t.response = xxe;
        rejected(t, good());
        String bomb = "<!DOCTYPE lolz [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">&b;</GetCallerIdentityResponse>";
        t.response = bomb;
        rejected(t, good());
        t.response = "not xml";
        rejected(t, good());
        t.response = "<a/>";
        rejected(t, good());
    }

    @Test
    void aResponseWithTwoAccountElementsIsAmbiguousAndRejected() {
        FakeTransport t = new FakeTransport();
        t.response = xml("123456789012", GOOD_ARN).replace("</GetCallerIdentityResult>", "<Account>999999999999</Account></GetCallerIdentityResult>");
        rejected(t, good());
    }

    @Test
    void theRealTransportDoesNotFollowRedirects() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", ex -> {
            followed.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirector.start();
        try {
            StsTransport.Response r = new HttpStsTransport().post(URI.create("http://127.0.0.1:" + redirector.getAddress().getPort() + "/"),
                    Map.of("content-type", "application/x-www-form-urlencoded"), "x");
            assertEquals(302, r.status());
            assertEquals(0, followed.get(), "the redirect target must never be contacted");
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }
}
