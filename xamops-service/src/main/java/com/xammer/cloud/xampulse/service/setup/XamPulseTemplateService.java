package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CloudFormation templates and install snippets that ship with this release (copied from /cloudformation at
 * build time), their SHA-256, and the quick-create links that open them in the customer's console. The ExternalId
 * travels in the link because the console has no other way to pre-fill a NoEcho parameter; links are therefore
 * never logged.
 */
@Service("xpTemplateService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseTemplateService {

    public static final String TEMPLATE_VERSION = "1.0.0";
    private static final String BASE = "xampulse/templates/";

    public record Template(String fileName, String content, String sha256) {}

    private final XamPulseProperties props;
    private final Map<String, Template> cache = new ConcurrentHashMap<>();

    public XamPulseTemplateService(XamPulseProperties props) {
        this.props = props;
    }

    public boolean uat() {
        return props.getSetup().isUat();
    }

    /** Value of the templates' ResourceSuffix default for this environment. */
    public String resourceSuffix() {
        return uat() ? "-uat" : "";
    }

    public Template template(boolean regional) {
        String file = "xamops-xampulse" + (regional ? "-region" : "") + (uat() ? "-uat" : "") + ".yml";
        return cache.computeIfAbsent(file, f -> {
            String content = read(BASE + f);
            return new Template(f, content, sha256(content));
        });
    }

    public String templateUrl(Template t) {
        String base = props.getSetup().getTemplateBaseUrl();
        if (base == null || base.isBlank()) {
            throw XamPulseException.conflict("Template hosting is not configured (xampulse.setup.template-base-url).");
        }
        if (!base.startsWith("https://")) {
            throw XamPulseException.conflict("xampulse.setup.template-base-url must be an https URL.");
        }
        return base.replaceAll("/+$", "") + "/" + t.fileName();
    }

    public static String stackName(String awsAccountId) {
        return "XamOpsXamPulse-" + awsAccountId;
    }

    public static String regionalStackName(String awsAccountId, String region) {
        return "XamOpsXamPulseRegion-" + awsAccountId + "-" + region;
    }

    public String quickCreateUrl(String region, String templateUrl, String stackName, Map<String, String> params) {
        StringBuilder sb = new StringBuilder("https://").append(region).append(".console.aws.amazon.com/cloudformation/home?region=")
                .append(enc(region)).append("#/stacks/quickcreate?templateURL=").append(enc(templateUrl))
                .append("&stackName=").append(enc(stackName));
        params.forEach((k, v) -> {
            if (v != null) {
                sb.append("&param_").append(k).append('=').append(enc(v));
            }
        });
        return sb.toString();
    }

    /**
     * Console "Update stack" flow for an existing stack (quick-create refuses an existing stack name). The console
     * keeps every parameter's previous value, so the caller shows the customer which parameters to change.
     * {@code stackIdOrName} should be the stack ARN when known.
     */
    public String updateStackUrl(String region, String stackIdOrName) {
        return "https://" + region + ".console.aws.amazon.com/cloudformation/home?region=" + enc(region)
                + "#/stacks/update/template?stackId=" + enc(stackIdOrName);
    }

    /** Console list of stacks in a region; the customer deletes the stack from there. */
    public static String stacksConsoleUrl(String region, String stackName) {
        return "https://" + region + ".console.aws.amazon.com/cloudformation/home?region=" + enc(region)
                + "#/stacks?filteringText=" + enc(stackName) + "&filteringStatus=active&viewNested=true";
    }

    /** Linux install script with the document parameters filled in, for GET /agentinstall/xampulse.sh. */
    public String installScript(String releaseBaseUrl, String desiredStateUrl) {
        String script = read(BASE + "xampulse/ssm-snippets/install-linux.sh");
        return script.replace("{{ ReleaseBaseUrl }}", shellSafe(releaseBaseUrl)).replace("{{ DesiredStateUrl }}", shellSafe(desiredStateUrl));
    }

    public String windowsPlaceholderScript() {
        return read(BASE + "xampulse/ssm-snippets/install-windows.ps1");
    }

    public String desiredStateUrl() {
        String api = props.getSetup().getApiBaseUrl();
        if (api == null || api.isBlank()) {
            throw XamPulseException.conflict("xampulse.setup.api-base-url is not configured.");
        }
        return api.replaceAll("/+$", "") + "/api/v1/agent/xampulse/desired-state";
    }

    public String releaseBaseUrl() {
        String r = props.getSetup().getReleaseBaseUrl();
        if (r == null || r.isBlank()) {
            throw XamPulseException.conflict("xampulse.setup.release-base-url is not configured.");
        }
        return r.replaceAll("/+$", "");
    }

    /** Only characters that cannot break out of a double-quoted shell string. */
    static String shellSafe(String url) {
        if (url == null || !url.matches("^https://[A-Za-z0-9./_:-]+$")) {
            throw XamPulseException.conflict("A configured install URL is not a plain https URL.");
        }
        return url;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String read(String resource) {
        try (InputStream in = XamPulseTemplateService.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
