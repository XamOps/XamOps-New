package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.dto.XpPermissionsDiff;
import com.xammer.cloud.xampulse.dto.XpRedaction;
import com.xammer.cloud.xampulse.dto.XpTrustPanel;
import com.xammer.cloud.xampulse.dto.XpTrustWhere;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the trust panel: what is and is not collected, where it lives, who can read it, and the exact grants the
 * XamOps connection role already holds. The grants list is parsed from the connection-role template that ships
 * with this release, so the text cannot drift from what the role really allows.
 */
@Service("xpTrustPanelService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class TrustPanelService {

    /** Required copy (plan 3.5). Keep wording stable; the tests pin it. */
    public static final String CONNECTION_ROLE_NOTE = "Your existing XamOps connection role has account-wide S3 read through AWS ReadOnlyAccess."
            + " In CREATE mode the bucket policy blocks it from the archive prefix."
            + " In EXISTING mode you can apply the deny snippet shown in the preview.";

    private static final Pattern READ_VERB = Pattern.compile(
            "^(Get|List|Describe|View|Lookup|BatchGet|Query|Scan|Search|Head|Read|Select|Check|Test|Estimate|Preview|Simulate)");
    private static final String ROLE_TEMPLATE = "xampulse/templates/xamops-readonly-role.yml";
    private static final String CONNECTION_ROLE_LOGICAL_ID = "XamOpsReadOnlyRole";

    private final XamPulseProperties props;
    private volatile List<String> grantsCache;

    public TrustPanelService(XamPulseProperties props) {
        this.props = props;
    }

    public XpTrustPanel panel(XpAccountConfig config, String templateSha256) {
        int hotDays = config == null || config.getHotDays() == 0 ? 14 : config.getHotDays();
        String bucket = config == null ? null : config.getBucketName();
        String region = config == null ? null : config.getBucketRegion();
        String kms = config == null ? null : (config.getKmsKeyArn() == null || config.getKmsKeyArn().isBlank() ? "AWS managed or none" : config.getKmsKeyArn());
        return new XpTrustPanel(
                List.of("Host CPU, memory, disk and network metrics",
                        "Process names and resource use (command-line arguments are off)",
                        "Logs only from the sources you tick (defaults: syslog, journald, auth, kern, Docker)"),
                List.of("Environment variables", "File contents outside the log paths you configure", "Process command lines",
                        "Instance user-data", "IAM credentials", "Database contents", "Keystrokes or screen data"),
                List.of(new XpRedaction("Runs on your host before data leaves it",
                        "Card numbers, AWS access keys, JWTs, bearer tokens, emails and password-like key/value pairs are masked. You can add rules.")),
                new XpTrustWhere(props.getHotRegion().isBlank() ? null : props.getHotRegion(), hotDays, bucket, region, kms),
                List.of("XamOps reads telemetry through the XamPulse archive role, which needs the ExternalId only you and XamOps hold.",
                        CONNECTION_ROLE_NOTE,
                        "Your users reach the data through their normal XamOps account access.",
                        "Support access (impersonation) is written to the audit trail."),
                List.of("Pause collection per instance.",
                        "Disconnect: agents are told to uninstall, tokens are revoked, and the install document stops reinstalling them.",
                        "The archive stays in your bucket until you delete it. Hot data can be purged on request."),
                new XpPermissionsDiff(
                        List.of("The XamPulse archive role can write and read only under xamops-xampulse/ in the one bucket you choose, and cannot delete objects."
                                        + " Deletion happens only through lifecycle rules you control, or by you.",
                                "Creating the global stack needs CAPABILITY_NAMED_IAM."),
                        CONNECTION_ROLE_NOTE,
                        connectionRoleGrants(),
                        "XamOps cannot run commands on your instances; the agent is installed by your own AWS Systems Manager association."),
                templateSha256);
    }

    /** Write-capable grants of the connection role, one readable line each. */
    public List<String> connectionRoleGrants() {
        List<String> g = grantsCache;
        if (g == null) {
            g = parseGrants(loadRoleTemplate());
            grantsCache = g;
        }
        return g;
    }

    @SuppressWarnings("unchecked")
    public static List<String> parseGrants(String roleTemplateYaml) {
        Map<String, Object> tpl = CfnYaml.parse(roleTemplateYaml);
        Map<String, Object> resources = (Map<String, Object>) tpl.get("Resources");
        Object role = resources == null ? null : resources.get(CONNECTION_ROLE_LOGICAL_ID);
        List<String> out = new ArrayList<>();
        if (!(role instanceof Map)) {
            return out;
        }
        Object props = ((Map<String, Object>) role).get("Properties");
        Object policies = props instanceof Map ? ((Map<String, Object>) props).get("Policies") : null;
        if (!(policies instanceof List)) {
            return out;
        }
        for (Object p : (List<Object>) policies) {
            Object doc = p instanceof Map ? ((Map<String, Object>) p).get("PolicyDocument") : null;
            Object statements = doc instanceof Map ? ((Map<String, Object>) doc).get("Statement") : null;
            if (!(statements instanceof List)) {
                continue;
            }
            for (Object s : (List<Object>) statements) {
                if (!(s instanceof Map)) {
                    continue;
                }
                Map<String, Object> st = (Map<String, Object>) s;
                if (!"Allow".equals(st.get("Effect"))) {
                    continue;
                }
                List<String> writes = new ArrayList<>();
                for (Object a : asList(st.get("Action"))) {
                    if (a instanceof String act && !act.equals("*") && !isRead(act)) {
                        writes.add(act);
                    }
                }
                if (writes.isEmpty()) {
                    continue;
                }
                List<String> res = new ArrayList<>();
                for (Object r : asList(st.get("Resource"))) {
                    res.add(resourceText(r));
                }
                out.add(String.join(", ", writes) + " on " + String.join(", ", res));
            }
        }
        return List.copyOf(out);
    }

    static boolean isRead(String action) {
        String verb = action.contains(":") ? action.substring(action.indexOf(':') + 1) : action;
        return READ_VERB.matcher(verb).find();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (o == null) {
            return List.of();
        }
        return o instanceof List ? (List<Object>) o : List.of(o);
    }

    @SuppressWarnings("unchecked")
    private static String resourceText(Object r) {
        if (r instanceof String s) {
            return s;
        }
        if (r instanceof Map<?, ?> m) {
            Object sub = ((Map<String, Object>) m).get("Fn::Sub");
            if (sub instanceof String s) {
                return s.replace("${AWS::AccountId}", "<your account>").replace("${AWS::StackName}", "<stack>");
            }
            Object ref = ((Map<String, Object>) m).get("Ref");
            if (ref instanceof String s) {
                return "<" + s + ">";
            }
        }
        return "(resource defined in the template)";
    }

    private static String loadRoleTemplate() {
        try (InputStream in = TrustPanelService.class.getClassLoader().getResourceAsStream(ROLE_TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + ROLE_TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
