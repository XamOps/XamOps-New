package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.dto.agent.EnrollRequest;
import com.xammer.cloud.xampulse.dto.agent.StsRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an agent's SigV4-signed {@code sts:GetCallerIdentity} proof and replays it to STS. Everything that would
 * let the proof reach anything but AWS is refused first: host allow-list, https, no user-info or port, fixed path,
 * fixed body, no redirects. The nonce must be a signed header, which is what ties the proof to one challenge.
 * The instance id comes from the assumed-role session name, which for EC2 instance profiles is the instance id.
 */
@Service("xpStsIdentityVerifier")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class StsIdentityVerifier {

    public static final String NONCE_HEADER = "x-xampulse-nonce";
    static final String EXPECTED_BODY = "Action=GetCallerIdentity&Version=2011-06-15";
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");
    private static final Pattern REGIONAL_HOST = Pattern.compile("^sts\\.([a-z0-9-]+)\\.amazonaws\\.com$");
    private static final Pattern ASSUMED_ROLE_ARN = Pattern.compile("^arn:aws:sts::(\\d{12}):assumed-role/([A-Za-z0-9+=,.@_-]{1,64})/(i-[0-9a-f]{8,17})$");
    private static final Set<String> FORWARDED = Set.of("content-type", "x-amz-date", "x-amz-security-token", "authorization", NONCE_HEADER);
    private static final String STS_NS = "https://sts.amazonaws.com/doc/2011-06-15/";

    public record Identity(String accountId, String arn, String instanceId) {}

    /** Any reason the proof is not acceptable. The message is for logs only and is never sent to the agent. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    private final StsTransport transport;

    public StsIdentityVerifier(StsTransport transport) {
        this.transport = transport;
    }

    public Identity verify(EnrollRequest req) {
        StsRequest sts = req.stsRequest();
        if (sts == null || req.nonce() == null || req.region() == null || !REGION.matcher(req.region()).matches()) {
            throw new Rejected("incomplete request");
        }
        URI uri = checkUrl(sts.url(), req.region());
        if (!"POST".equals(sts.method())) {
            throw new Rejected("method");
        }
        if (!EXPECTED_BODY.equals(sts.body())) {
            throw new Rejected("body");
        }
        Map<String, String> headers = normalise(sts.headers());
        if (!req.nonce().equals(headers.get(NONCE_HEADER))) {
            throw new Rejected("nonce header missing or different");
        }
        String auth = headers.get("authorization");
        if (auth == null || !signedHeaders(auth).contains(NONCE_HEADER)) {
            throw new Rejected("nonce is not a signed header");
        }
        if (!signedHeaders(auth).contains("x-amz-date")) {
            throw new Rejected("date is not signed");
        }
        Map<String, String> forward = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (FORWARDED.contains(k)) {
                forward.put(k, v);
            }
        });
        forward.putIfAbsent("content-type", "application/x-www-form-urlencoded; charset=utf-8");
        StsTransport.Response r;
        try {
            r = transport.post(uri, forward, sts.body());
        } catch (IOException e) {
            throw new Rejected("sts unreachable");
        }
        if (r.status() != 200) {
            throw new Rejected("sts status " + r.status());
        }
        return parse(r.body());
    }

    static URI checkUrl(String url, String region) {
        if (url == null || url.length() > 200) {
            throw new Rejected("url");
        }
        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            throw new Rejected("url syntax");
        }
        if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || u.getPort() != -1 || u.getRawQuery() != null
                || u.getRawFragment() != null || u.getHost() == null) {
            throw new Rejected("url shape");
        }
        String path = u.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new Rejected("url path");
        }
        String host = u.getHost().toLowerCase(Locale.ROOT);
        if (!"sts.amazonaws.com".equals(host)) {
            Matcher m = REGIONAL_HOST.matcher(host);
            if (!m.matches() || !m.group(1).equals(region)) {
                throw new Rejected("url host");
            }
        }
        return u;
    }

    private static Map<String, String> normalise(Map<String, List<String>> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null && !v.isEmpty() && v.get(0) != null) {
                    String val = v.get(0);
                    if (val.length() > 2048 || val.indexOf('\n') >= 0 || val.indexOf('\r') >= 0) {
                        throw new Rejected("header value");
                    }
                    out.put(k.toLowerCase(Locale.ROOT), val);
                }
            });
        }
        return out;
    }

    static Set<String> signedHeaders(String authorization) {
        Matcher m = Pattern.compile("SignedHeaders=([a-z0-9;-]+)").matcher(authorization);
        if (!m.find()) {
            return Set.of();
        }
        return Set.of(m.group(1).split(";"));
    }

    static Identity parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            Document d = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String account = text(d, "Account");
            String arn = text(d, "Arn");
            if (account == null || arn == null) {
                throw new Rejected("sts response");
            }
            Matcher m = ASSUMED_ROLE_ARN.matcher(arn);
            if (!m.matches() || !m.group(1).equals(account)) {
                throw new Rejected("not an EC2 instance-profile identity");
            }
            return new Identity(account, arn, m.group(3));
        } catch (Rejected r) {
            throw r;
        } catch (Exception e) {
            throw new Rejected("sts response unreadable");
        }
    }

    private static String text(Document d, String name) {
        NodeList l = d.getElementsByTagNameNS(STS_NS, name);
        if (l.getLength() != 1) {
            return null;
        }
        String t = l.item(0).getTextContent();
        return t == null ? null : t.trim();
    }
}
