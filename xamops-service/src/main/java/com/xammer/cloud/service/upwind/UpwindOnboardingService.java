package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service onboarding of a customer AWS account into Xammer's single Upwind organization.
 *
 * <h2>Why this can exist at all</h2>
 * Upwind exposes no usable cloud-account API: every {@code /cloud-accounts} and
 * {@code /byoc-credentials} path 404s or 401s on {@code api.upwind.io}, and account registration
 * actually lives on a different service ({@code integration.upwind.io}) that our API-type
 * credential gets a token for but with zero permissions. Probed 2026-08-19.
 *
 * <p>None of that matters, because Upwind's own CloudFormation template does the work in-account:
 * <ul>
 *   <li>{@code Custom::UpwindGeneratedToken} calls Upwind and returns a freshly generated
 *       {@code ExternalId} — so the external ID is NOT something a Xammer admin pre-mints in the
 *       Upwind console. It is unique per stack, by construction.</li>
 *   <li>{@code Custom::UpwindRegisteredAccount} posts that external ID plus the created role ARN
 *       back to Upwind, registering the account.</li>
 * </ul>
 * So one shared "Cloud account" credential serves every customer: per-account uniqueness comes
 * from the generated external ID, not from the credential. That is what makes this fully
 * self-service with no Upwind console visit per customer.
 *
 * <h2>The credential is ours, never the customer's to enter</h2>
 * The client id/secret are injected into the launch URL server-side. They are valid against
 * Xammer's whole Upwind organization, so a UI that asked the customer to type them would be
 * handing every customer a credential for every other customer's onboarding. They are also
 * needed on stack UPDATE and DELETE — the custom resources run on every lifecycle event — so
 * they cannot be rotated away once customers hold stacks.
 */
@Service
@Slf4j
public class UpwindOnboardingService {

    /**
     * Upwind's published onboarding template.
     *
     * Pinned as a property so a customer onboarding tomorrow can be given the same template we
     * tested. {@code latest.yaml} is a moving target: Upwind can change its parameters without
     * notice, and the parameter names below would silently stop matching.
     */
    @Value("${upwind.onboarding.template-url:https://s3.amazonaws.com/cfn.upwind.io/onboarding/latest.yaml}")
    private String templateUrl;

    /** Upwind's own AWS account, the trusted principal in the role's trust policy. */
    @Value("${upwind.onboarding.trusted-account:340457201789}")
    private String trustedAccount;

    /** Upwind region: us, eu or me. Must match the API base the sync talks to. */
    @Value("${upwind.onboarding.region:us}")
    private String upwindRegion;

    /**
     * Upwind's ECS sensor CloudFormation template for the EC2 launch type.
     *
     * Empty by default and deliberately not guessed. The account-onboarding template has a known
     * published URL; the ECS ones have not been given to us, and self-hosting a copy is the wrong
     * answer — the templates pin {@code registry.upwind.io/images/agent:0.130.3} and
     * {@code cluster-agent:0.64.4}, so a saved copy silently deploys an ageing sensor. When the
     * URL is set, the UI can offer a pre-filled quick-create link; until then it shows the
     * resolved parameters for the customer to paste into Upwind's own console.
     */
    @Value("${upwind.onboarding.ecs-template-url:}")
    private String ecsTemplateUrl;

    /** Upwind's ECS template for the Fargate launch type — a different stack, not a parameter. */
    @Value("${upwind.onboarding.ecs-fargate-template-url:}")
    private String ecsFargateTemplateUrl;

    /**
     * Region the stack is deployed into.
     *
     * Upwind's template sources its onboarding Lambda from
     * {@code upwind-serverless-functions-${AWS::Region}}, so this cannot be left to the
     * customer's current console region — an unsupported region fails the stack with an opaque
     * "bucket does not exist".
     */
    @Value("${upwind.onboarding.stack-region:us-east-1}")
    private String stackRegion;

    /** Cloud-account-type client id. NOT the API sync credential. */
    @Value("${upwind.onboarding.client-id:}")
    private String onboardingClientId;

    @Value("${upwind.onboarding.client-secret:}")
    private String onboardingClientSecret;

    /**
     * Sensor-type ("agent") client id, for {@link #renderSensorInstallCommand()}. Verified live
     * 2026-08-24: zero permissions on both the integration and data hosts, so sharing it is the
     * same call as sharing the Cloud account credential — a leak risks a rogue sensor enrolling,
     * not data exposure.
     */
    @Value("${upwind.sensor.client-id:}")
    private String sensorClientId;

    @Value("${upwind.sensor.client-secret:}")
    private String sensorClientSecret;

    @Value("${upwind.org-id:}")
    private String orgId;

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final UpwindApiClient apiClient;
    private final UpwindIntegrationClient integrationClient;
    private final com.xammer.cloud.repository.ClientRepository clientRepository;

    public UpwindOnboardingService(CloudAccountRepository cloudAccountRepository,
                                   UpwindAccountConnectionRepository connectionRepo,
                                   UpwindApiClient apiClient,
                                   UpwindIntegrationClient integrationClient,
                                   com.xammer.cloud.repository.ClientRepository clientRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.connectionRepo = connectionRepo;
        this.apiClient = apiClient;
        this.integrationClient = integrationClient;
        this.clientRepository = clientRepository;
    }

    /**
     * Whether this client is permitted to run self-service onboarding at all.
     *
     * Absent client row reads as allowed, matching the column default: a lookup failure must not
     * become a silent feature withdrawal.
     */
    public boolean isAllowedForClient(Long clientId) {
        return clientRepository.findById(clientId)
                .map(com.xammer.cloud.domain.Client::isXamSecureOnboardingEnabled)
                .orElse(true);
    }

    private void requireAllowed(Long clientId) {
        if (!isAllowedForClient(clientId)) {
            throw new AccessDeniedException(
                    "Self-service security onboarding is not enabled for this account. "
                    + "Contact your XamOps administrator.");
        }
    }

    /** Whether a launch URL can be produced at all, i.e. whether the shared credential is set. */
    public boolean isConfigured() {
        return notBlank(onboardingClientId) && notBlank(onboardingClientSecret) && notBlank(orgId);
    }

    /** Whether the shared Sensor credential is set, so a host/cluster install command can be rendered. */
    public boolean isSensorConfigured() {
        return notBlank(sensorClientId) && notBlank(sensorClientSecret);
    }

    /**
     * Renders Upwind's own host sensor install command, with the shared Sensor credential
     * filled in.
     *
     * <p>Copied verbatim from the Upwind console ("Connect a host" -> Step 3), not reverse
     * engineered: the flag names, the {@code scanner-v2=true} extra config and the pipe-to-bash
     * shape are Upwind's, not ours to invent. It is NOT parameterized per account or cluster —
     * unlike the CloudFormation stack, the same script is correct for every host, so there is
     * nothing here to key by cloud account id.
     *
     * <p>Kubernetes uses a different command and IS parameterized — see
     * {@link #renderKubernetesSurveyCommand}.
     *
     * <p>The credential is embedded directly rather than exported once and referenced, matching
     * what the console itself renders — Upwind's own template exports {@code CLIENT_SECRET} as a
     * literal in the script, so following their shape exactly is what "copied verbatim" means
     * here.
     */
    public String renderSensorInstallCommand() {
        if (!isSensorConfigured()) {
            throw new IllegalStateException(
                    "Sensor deployment is not configured: set upwind.sensor.client-id and "
                    + "upwind.sensor.client-secret (type 'Sensor' credentials from the Upwind "
                    + "console — not the Cloud account or API credentials).");
        }
        return "export CLIENT_ID=\"" + sensorClientId + "\"\n"
                + "export CLIENT_SECRET=\"" + sensorClientSecret + "\"\n\n"
                + "curl -s https://get.upwind.io/sensor.sh | \\\n"
                + "UPWIND_CLIENT_ID=\"$CLIENT_ID\" \\\n"
                + "UPWIND_CLIENT_SECRET=\"$CLIENT_SECRET\" \\\n"
                + "UPWIND_AGENT_EXTRA_CONFIG=\"scanner-v2=true\" \\\n"
                + "bash -s";
    }

    /**
     * Upwind's {@code --cloud-provider} token per provider we hold accounts for.
     *
     * Only {@code aws} is VERIFIED — it is what the console emitted in the Kubernetes survey
     * command we copied. The other two are inferred from Upwind's own vocabulary elsewhere in the
     * API ({@code cloud_provider} values, credential {@code provider.name}) and should be
     * confirmed against the console before being offered to a customer. Anything not listed is
     * rejected rather than guessed: a wrong provider flag produces a sensor that silently attaches
     * telemetry to the wrong cloud.
     */
    private static final Map<String, String> K8S_PROVIDER_FLAG = Map.of(
            "AWS", "aws",
            "AZURE", "azure",
            "GCP", "gcp");

    /**
     * Cluster names we will interpolate into a shell command.
     *
     * The customer types this and then runs the result in their own terminal, so an unvalidated
     * value is a command-injection vector we would be handing them ready-made. Kubernetes object
     * names are already restricted to this shape, so rejecting anything else costs nothing.
     */
    private static final java.util.regex.Pattern CLUSTER_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    /**
     * Resolves the CloudFormation parameters Upwind's ECS sensor stack needs, for one of the
     * caller's own accounts.
     *
     * <p>Upwind's ECS template ("Connect an AWS ECS cluster" -> EC2 launch type) asks for
     * {@code ClusterName}, {@code VPCID}, {@code VPCCIDRBlock} and {@code Subnets} on top of the
     * sensor credential. Three of those four are the reason this deployment fails: the template's
     * own Rules reject an empty ClusterName or VPCID, {@code VPCCIDRBlock} has no default and
     * feeds the security-group ingress rule, and {@code Subnets} must be PRIVATE subnets with NAT
     * access or the cluster manager's ENI comes up with no route out. A customer filling those in
     * by hand, from memory, in a console they visit once, is where the time goes.
     *
     * <p>So this reads them out of the inventory instead. The candidates come from the live
     * relationship search rather than our synced catalog because the catalog does not carry
     * {@code cidr_block} or {@code subnet_id} — only the relationship endpoint returns those.
     *
     * <p><b>What this deliberately does NOT claim:</b> which subnets are private. Route-table
     * associations are not exposed in the inventory (route tables carry no VPC reference at all),
     * so private-ness cannot be determined and guessing it would produce a stack that deploys and
     * then silently fails to report. Subnets are listed per VPC for the customer to choose from,
     * and whether the VPC has a NAT gateway at all IS reported, since that much is knowable.
     *
     * <p><b>The response contains the shared Sensor client secret.</b> Same handling as the
     * CloudFormation launch URL and the Kubernetes command.
     */
    public Map<String, Object> resolveEcsDeployment(Long clientId, String cloudAccountId,
                                                    String launchType) {
        if (!isSensorConfigured()) {
            throw new IllegalStateException(
                    "Sensor deployment is not configured: set upwind.sensor.client-id and "
                    + "upwind.sensor.client-secret (type 'Sensor' credentials from the Upwind "
                    + "console — not the Cloud account or API credentials).");
        }
        requireAllowed(clientId);
        CloudAccount account = ownedAccount(clientId, cloudAccountId);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new IllegalArgumentException(
                    "ECS runs on AWS only; this account is " + account.getProvider() + ".");
        }
        String launch = launchType == null || launchType.isBlank()
                ? "EC2" : launchType.trim().toUpperCase();
        if (!"EC2".equals(launch) && !"FARGATE".equals(launch)) {
            throw new IllegalArgumentException("launchType must be EC2 or FARGATE.");
        }
        boolean fargate = "FARGATE".equals(launch);

        // Clusters, VPCs (for the CIDR) and network interfaces (for subnet ids), from the one
        // endpoint that returns those fields. Failures degrade to an empty candidate list rather
        // than an error: the customer can still fill the form in by hand.
        List<String> clusters = new java.util.ArrayList<>();
        List<Map<String, Object>> vpcs = new java.util.ArrayList<>();
        try {
            for (JsonNode c : apiClient.searchAssetsByLabel("aws_ecs_cluster", cloudAccountId, 500)) {
                String name = c.path("name").asText(null);
                if (name != null && !name.isBlank()) clusters.add(name);
            }
            // subnet ids and NAT presence per VPC
            Map<String, java.util.Set<String>> subnetsByVpc = new LinkedHashMap<>();
            for (JsonNode e : apiClient.searchAssetsByLabel(
                    "aws_elastic_network_interface", cloudAccountId, 5000)) {
                String vpc = e.path("vpc_id").asText(null);
                String subnet = e.path("subnet_id").asText(null);
                if (vpc == null || subnet == null || vpc.isBlank() || subnet.isBlank()) continue;
                subnetsByVpc.computeIfAbsent(vpc, k -> new java.util.LinkedHashSet<>()).add(subnet);
            }
            boolean anyNat = !apiClient.searchAssetsByLabel(
                    "aws_nat_gateway", cloudAccountId, 200).isEmpty();

            for (JsonNode v : apiClient.searchAssetsByLabel("aws_vpc", cloudAccountId, 500)) {
                String vpcId = UpwindAssetIds.canonicalResourceId(
                        v.path("cloud_resource_id").asText(null));
                if (vpcId == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vpcId", vpcId);
                row.put("name", v.path("name").asText(null));
                row.put("cidrBlock", v.path("cidr_block").asText(null));
                row.put("region", v.path("region").asText(null));
                row.put("isDefault", v.path("is_default").asBoolean(false));
                row.put("subnets", new java.util.ArrayList<>(
                        subnetsByVpc.getOrDefault(vpcId, java.util.Set.of())));
                // Account-level, not per VPC: NAT gateways carry no VPC reference, so this says
                // "this account has NAT somewhere", which is weaker than it looks and is labelled
                // as such rather than presented as a per-VPC verdict.
                row.put("accountHasNatGateway", anyNat);
                vpcs.add(row);
            }
        } catch (Exception e) {
            log.warn("[Upwind] ECS parameter resolution failed for account {}: {}",
                    cloudAccountId, e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("launchType", launch);
        out.put("clusters", clusters);
        out.put("vpcs", vpcs);

        // Template parameters the customer does not have to work out.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("UpwindClientId", sensorClientId);
        params.put("UpwindClientSecret", sensorClientSecret);
        // Upwind's region (us|eu|me|ap), NOT the AWS stack region — the template's Region
        // parameter selects which auth/agent endpoints the sensor talks to, and passing an AWS
        // region here would fail its AllowedValues check.
        params.put("Region", upwindRegion == null || upwindRegion.isBlank() ? "us" : upwindRegion);
        out.put("parameters", params);
        out.put("templateUrl", (fargate ? ecsFargateTemplateUrl : ecsTemplateUrl) == null
                ? "" : (fargate ? ecsFargateTemplateUrl : ecsTemplateUrl));

        // What the stack actually deploys. The two launch types are genuinely different stacks,
        // not one stack with a flag, and the difference decides what telemetry the customer gets.
        //
        // EC2 runs a privileged sensor and a scanner as DAEMON services on the container
        // instances, plus a cluster manager. Fargate cannot run a privileged host daemon at all,
        // so its template deploys ONLY the cluster manager, with a tracer sidecar sharing the task
        // and SYS_PTRACE. Saying so matters: someone expecting per-host runtime coverage on
        // Fargate will not get it in the shape EC2 gives it.
        out.put("components", fargate
                ? List.of("Cluster manager (Fargate task, with tracer sidecar)")
                : List.of("Sensor (DAEMON on every container instance, privileged)",
                          "Scanner (DAEMON, vulnerability scanning — can be disabled)",
                          "Cluster manager (one task)"));
        // Security-group ingress the cluster manager needs from the VPC CIDR. EC2 additionally
        // needs 8444 because its sensors report over grpcs; Fargate's tracer path uses 8082 only.
        out.put("securityGroupPorts", fargate ? List.of(8082) : List.of(8082, 8444));

        List<String> notes = new java.util.ArrayList<>();
        notes.add("Subnets must be PRIVATE subnets with NAT gateway access — the cluster "
                + "manager's network interface needs a route out.");
        notes.add("The template pins container image versions, so use Upwind's current template "
                + "rather than a saved copy.");
        if (fargate) {
            notes.add("Fargate deploys the cluster manager only. There is no per-host sensor or "
                    + "scanner, because Fargate does not permit a privileged host daemon — so "
                    + "runtime coverage on Fargate tasks differs from EC2.");
            notes.add("ClusterManagerCPUArchitecture must match your tasks: X86_64 or ARM64.");
            notes.add("MultiCluster=true lets one cluster manager read several ECS clusters, but "
                    + "it widens the task role's ecs:DescribeTasks permission to every cluster in "
                    + "the account. Leave it false unless you need it.");
        } else {
            notes.add("The sensor runs privileged with SYS_ADMIN and NET_ADMIN and mounts the "
                    + "Docker socket on each container instance — worth knowing before approval.");
            notes.add("ScannerEnabled=false skips the vulnerability scanner if you only want "
                    + "runtime detection.");
        }
        out.put("notes", notes);
        return out;
    }

    /** CloudFormation stack names allow letters, digits and hyphens, and must start with a letter. */
    private static String ecsStackName(String prefix, String clusterName) {
        String safe = clusterName.replaceAll("[^A-Za-z0-9-]", "-");
        // ECS cluster names permit underscores; stack names do not, so the substitution above is
        // required rather than cosmetic — an underscore fails stack creation outright.
        return (prefix + "-" + safe).replaceAll("-{2,}", "-");
    }

    /**
     * Pre-filled CloudFormation quick-create URL for the ECS sensor stack.
     *
     * <p>Built server-side for the same reason as the account-onboarding link: the query string
     * carries the shared Sensor credential, so it is a credential itself — handed to the
     * authenticated customer over TLS, never logged, never persisted.
     *
     * <p>Everything the customer chose is validated before it goes into a URL. {@code Subnets} is a
     * {@code List<String>} in the template, which quick-create passes as a comma-separated value.
     *
     * @param launchType EC2 or FARGATE — two different templates, not one with a flag
     */
    public Map<String, Object> ecsLaunchUrl(Long clientId, String cloudAccountId, String launchType,
                                            String clusterName, String vpcId, String vpcCidr,
                                            List<String> subnets, String region) {
        if (!isSensorConfigured()) {
            throw new IllegalStateException("Sensor deployment is not configured.");
        }
        requireAllowed(clientId);
        CloudAccount account = ownedAccount(clientId, cloudAccountId);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new IllegalArgumentException("ECS runs on AWS only.");
        }
        boolean fargate = "FARGATE".equalsIgnoreCase(launchType);
        String template = fargate ? ecsFargateTemplateUrl : ecsTemplateUrl;
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(
                    "The ECS " + (fargate ? "Fargate" : "EC2") + " template URL is not configured "
                    + "(upwind.onboarding.ecs" + (fargate ? "-fargate" : "") + "-template-url).");
        }

        String cluster = clusterName == null ? "" : clusterName.trim();
        if (!CLUSTER_NAME.matcher(cluster).matches()) {
            throw new IllegalArgumentException("Cluster name is not a valid ECS cluster name.");
        }
        if (vpcId == null || !VPC_ID.matcher(vpcId.trim()).matches()) {
            throw new IllegalArgumentException("VPC id must look like vpc-0123456789abcdef.");
        }
        String cidr = vpcCidr == null ? "" : vpcCidr.trim();
        if (!CIDR.matcher(cidr).matches()) {
            throw new IllegalArgumentException("VPC CIDR must look like 10.0.0.0/16.");
        }
        List<String> subnetIds = subnets == null ? List.of() : subnets.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (subnetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one private subnet with NAT access is required.");
        }
        for (String s : subnetIds) {
            if (!SUBNET_ID.matcher(s).matches()) {
                throw new IllegalArgumentException(
                        "Subnet id must look like subnet-0123456789abcdef: " + s);
            }
        }

        String stackName = ecsStackName(fargate ? "upwind-ecs-fargate" : "upwind-ecs-sensor", cluster);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stackName", stackName);
        params.put("param_ClusterName", cluster);
        params.put("param_VPCID", vpcId.trim());
        params.put("param_VPCCIDRBlock", cidr);
        params.put("param_Subnets", String.join(",", subnetIds));
        params.put("param_UpwindClientId", sensorClientId);
        params.put("param_UpwindClientSecret", sensorClientSecret);
        params.put("param_Region", upwindRegion == null || upwindRegion.isBlank()
                ? "us" : upwindRegion);

        // The stack must be created in the region the cluster is in, not our default onboarding
        // region: the ECS service, the VPC and the subnets are all regional, and a stack in the
        // wrong region cannot see any of them. CloudAccount carries no region, so this comes from
        // the VPC the customer selected — the inventory reports a region per VPC.
        String stackIn = region == null || !AWS_REGION.matcher(region.trim()).matches()
                ? stackRegion : region.trim();

        StringBuilder q = new StringBuilder();
        q.append("templateURL=").append(enc(template));
        params.forEach((k, v) -> q.append('&').append(k).append('=').append(enc(v)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("launchUrl", "https://" + stackIn + ".console.aws.amazon.com/cloudformation/home"
                + "?region=" + enc(stackIn) + "#/stacks/quickcreate?" + q);
        out.put("stackName", stackName);
        out.put("launchType", fargate ? "FARGATE" : "EC2");
        out.put("region", stackIn);
        out.put("clusterName", cluster);
        log.info("[Upwind] ECS {} launch URL built for client {} account {} cluster {}",
                out.get("launchType"), clientId, cloudAccountId, cluster);
        return out;
    }

    /** e.g. ap-south-1, us-east-2. Rejected values fall back to the configured stack region. */
    private static final java.util.regex.Pattern AWS_REGION =
            java.util.regex.Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");

    private static final java.util.regex.Pattern VPC_ID =
            java.util.regex.Pattern.compile("^vpc-[0-9a-f]{6,}$");
    private static final java.util.regex.Pattern SUBNET_ID =
            java.util.regex.Pattern.compile("^subnet-[0-9a-f]{6,}$");
    /** Enough to reject a wrong value; CloudFormation validates the rest. */
    private static final java.util.regex.Pattern CIDR =
            java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$");

    /**
     * Managed Kubernetes clusters in one of the caller's own accounts, for the onboarding picker.
     *
     * <p>Upwind's own console offers a cluster dropdown and notes that it is "available only for
     * connected accounts" — the same constraint applies here, and for the same reason: the list
     * comes from the inventory, so a cluster only appears once the account is connected and has
     * been scanned. A free-text cluster name was the alternative, and a typo there produces a
     * survey that runs against nothing.
     *
     * <p>Measured 2026-09-07: {@code aws_eks_cluster} returns 3 clusters for account
     * 598515435126 with region, Kubernetes version, status and endpoint visibility attached, which
     * is more than a bare name — enough to tell two similarly named clusters apart, and to warn
     * before someone re-deploys onto a cluster that already reports a sensor.
     *
     * <p>AKS and GKE labels are queried too so this does not need revisiting when Azure and GCP
     * onboarding lands; both return zero today, which is correct rather than broken.
     */
    public Map<String, Object> resolveKubernetesClusters(Long clientId, String cloudAccountId) {
        requireAllowed(clientId);
        CloudAccount account = ownedAccount(clientId, cloudAccountId);

        List<Map<String, Object>> clusters = new java.util.ArrayList<>();
        for (String label : List.of("aws_eks_cluster", "azure_aks_cluster", "gcp_gke_cluster")) {
            try {
                for (JsonNode c : apiClient.searchAssetsByLabel(label, cloudAccountId, 500)) {
                    String name = c.path("name").asText(null);
                    if (name == null || name.isBlank()) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("label", label);
                    row.put("region", c.path("region").asText(null));
                    row.put("version", c.path("version").asText(null));
                    row.put("status", c.path("status").asText(null));
                    // Only claim a sensor when the catalog actually says so. This field is blank
                    // on most assets, and treating blank as "unprotected" would tell people to
                    // redeploy onto clusters that are already covered.
                    String protectedBy = c.path("uw_protected_by").asText(null);
                    row.put("protectedBy",
                            protectedBy == null || protectedBy.isBlank() ? null : protectedBy);
                    row.put("publicEndpoint", c.path("public_endpoint_enabled").asBoolean(false));
                    clusters.add(row);
                }
            } catch (Exception e) {
                log.warn("[Upwind] {} lookup failed for account {}: {}",
                        label, cloudAccountId, e.getMessage());
            }
        }
        clusters.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("provider", account.getProvider());
        out.put("clusters", clusters);
        return out;
    }

    /**
     * Renders Upwind's Kubernetes sensor SURVEY command for one of the caller's own accounts.
     *
     * <p>Copied from the Upwind console ("Connect Kubernetes Cluster" -> Automatic survey), with
     * the shared Sensor credential injected server-side. Flag names and the
     * {@code upwindctl.sh ... kubernetes survey} shape are Upwind's.
     *
     * <p>This is deliberately the SURVEY command rather than an install command. Upwind's flow is
     * two-phase: the survey inspects the cluster — proxy and metadata-service restrictions, core
     * count — and then emits an install command carrying resource limits and a replica count
     * tailored to that cluster. We cannot compute those numbers from outside the cluster, and
     * guessing them is how a sensor daemonset gets OOM-killed or under-replicated on a large
     * estate. So we hand over the survey and let Upwind's own tooling generate the install step.
     *
     * <p><b>The returned string contains the shared Sensor client secret.</b> Same handling as the
     * CloudFormation launch URL: straight to the authenticated customer over TLS, never logged,
     * never persisted.
     *
     * @throws AccessDeniedException    if the account does not belong to this client
     * @throws IllegalArgumentException on an unsupported provider or a malformed cluster name
     */
    public Map<String, Object> renderKubernetesSurveyCommand(
            Long clientId, String cloudAccountId, String clusterName) {
        if (!isSensorConfigured()) {
            throw new IllegalStateException(
                    "Sensor deployment is not configured: set upwind.sensor.client-id and "
                    + "upwind.sensor.client-secret (type 'Sensor' credentials from the Upwind "
                    + "console — not the Cloud account or API credentials).");
        }
        requireAllowed(clientId);
        // Tenancy: resolve through the client's own accounts rather than trusting the id on the
        // request, exactly as createLaunchUrl does.
        CloudAccount account = ownedAccount(clientId, cloudAccountId);

        String provider = account.getProvider() == null ? "" : account.getProvider().toUpperCase();
        String providerFlag = K8S_PROVIDER_FLAG.get(provider);
        if (providerFlag == null) {
            throw new IllegalArgumentException(
                    "Kubernetes sensor deployment is not available for provider '"
                    + account.getProvider() + "'. Supported: "
                    + String.join(", ", K8S_PROVIDER_FLAG.keySet()) + ".");
        }
        String cluster = clusterName == null ? "" : clusterName.trim();
        if (!CLUSTER_NAME.matcher(cluster).matches()) {
            throw new IllegalArgumentException(
                    "Cluster name must start with a letter or digit and contain only letters, "
                    + "digits, dots, dashes or underscores.");
        }

        String command = "curl -s https://get.upwind.io/upwindctl.sh | bash -s -- \\\n"
                + "  kubernetes survey \\\n"
                + "  --client-id=" + sensorClientId + " \\\n"
                + "  --client-secret=" + sensorClientSecret + " \\\n"
                + "  --cloud-provider=" + providerFlag + " \\\n"
                + "  --cloud-account-id=" + cloudAccountId + " \\\n"
                + "  --cluster-name=" + cluster;

        // Account id and cluster name only — the command itself carries the secret.
        log.info("[Upwind] rendered Kubernetes survey command for client {} account {} cluster {}",
                clientId, cloudAccountId, cluster);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("target", "kubernetes");
        out.put("phase", "survey");
        out.put("cloudAccountId", cloudAccountId);
        out.put("clusterName", cluster);
        out.put("cloudProvider", providerFlag);
        // Surfaced so the UI can tell the customer what happens next instead of leaving them at a
        // copied command: the survey prints an install command, and scan jobs must be permitted.
        out.put("requiresKubernetesJobs", true);
        return out;
    }

    /**
     * Builds the CloudFormation quick-create URL for one of the caller's own AWS accounts and
     * marks the connection PENDING.
     *
     * <p><b>The returned URL contains the shared client secret in its query string.</b> It must be
     * treated as a credential: handed straight to the authenticated customer over TLS, never
     * logged, never persisted, never emailed. Only the account id and stack name are stored.
     *
     * @throws AccessDeniedException if the account does not belong to this client — the whole
     *         point of resolving through the client's own accounts rather than trusting the id
     *         off the request.
     */
    @Transactional
    public Map<String, Object> createLaunchUrl(Long clientId, String cloudAccountId) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Upwind onboarding is not configured: set upwind.onboarding.client-id and "
                    + "upwind.onboarding.client-secret (type 'Cloud account' credentials from the "
                    + "Upwind console — not the API sync credential).");
        }
        requireAllowed(clientId);
        CloudAccount account = ownedAccount(clientId, cloudAccountId);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            throw new IllegalArgumentException(
                    "Self-service onboarding currently covers AWS only; this account is "
                    + account.getProvider() + ". GCP and Azure use different Upwind flows.");
        }

        String stackName = stackNameFor(cloudAccountId);
        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(UpwindAccountConnection::new);
        conn.setCloudAccountId(cloudAccountId);
        // Never downgrade a working connection: re-running onboarding on a CONNECTED account
        // (to pick up new Upwind permissions via a stack update) must not blank the tab out.
        if (!"CONNECTED".equals(conn.getStatus())) {
            conn.setStatus("PENDING");
        }
        conn.setStackRequestedAt(Instant.now());
        conn.setStackRegion(stackRegion);
        conn.setStackName(stackName);
        conn.setUpdatedAt(Instant.now());
        connectionRepo.save(conn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("launchUrl", quickCreateUrl(stackName));
        out.put("stackName", stackName);
        out.put("stackRegion", stackRegion);
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn.getStatus());
        return out;
    }

    /**
     * Upwind's console names these {@code upwind-readonly-permissions-<epoch>}. We use the account
     * id instead of a timestamp so re-running onboarding UPDATES the existing stack rather than
     * creating a second one — two stacks in one account would each try to register it and fight
     * over the role name.
     */
    private String stackNameFor(String cloudAccountId) {
        return "upwind-readonly-permissions-" + cloudAccountId;
    }

    private String quickCreateUrl(String stackName) {
        // KmsKeyArn / CredentialsSecretArn are deliberately left at their NOT_SET defaults: the
        // template's own Rules assertion accepts EITHER ClientId+ClientSecret OR
        // CredentialsSecretArn+KmsKeyArn, and the secret-ARN path additionally needs a
        // customer-managed KMS key and a secret that already exists in the customer's account.
        // Passing the credentials directly lets the template create the secret itself.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stackName", stackName);
        params.put("param_OrganizationId", orgId);
        params.put("param_TrustedAccount", trustedAccount);
        params.put("param_UpwindRegion", upwindRegion);
        params.put("param_ClientId", onboardingClientId);
        params.put("param_ClientSecret", onboardingClientSecret);

        StringBuilder q = new StringBuilder();
        q.append("templateURL=").append(enc(templateUrl));
        params.forEach((k, v) -> q.append('&').append(k).append('=').append(enc(v)));
        return "https://" + stackRegion + ".console.aws.amazon.com/cloudformation/home?region="
                + enc(stackRegion) + "#/stacks/quickcreate?" + q;
    }

    /**
     * Every Upwind registration, keyed by AWS account id, for callers that need several at once.
     *
     * Empty map on failure or when the integration credential is unset — callers must treat that
     * as "no information", not as "nothing is connected".
     */
    public Map<String, UpwindIntegrationClient.CloudCredential> registrationsByAccountId() {
        if (!integrationClient.isConfigured()) return Map.of();
        try {
            Map<String, UpwindIntegrationClient.CloudCredential> out = new LinkedHashMap<>();
            for (UpwindIntegrationClient.CloudCredential c : integrationClient.listCloudCredentials()) {
                if (c.cloudAccountId() != null) out.put(c.cloudAccountId(), c);
            }
            return out;
        } catch (Exception e) {
            log.warn("[Upwind] could not list registrations: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Upwind's own registration record for an account, when it has one.
     *
     * This is the authoritative connection state and is preferred over
     * {@link #reportsAssets(String)}: it distinguishes "registered but the scanner is off" from
     * "never registered", and it reports a freshly registered account as connected immediately
     * instead of waiting for the first scan to produce assets.
     */
    public Optional<UpwindIntegrationClient.CloudCredential> registrationFor(String cloudAccountId) {
        if (!integrationClient.isConfigured()) return Optional.empty();
        try {
            return integrationClient.findByCloudAccountId(cloudAccountId);
        } catch (Exception e) {
            // Unknown, not absent. Treating an API failure as "not registered" would let the
            // verifier and the disconnect path both act on a false negative.
            log.warn("[Upwind] could not read registration for account {}: {}",
                    cloudAccountId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fallback signal: does Upwind report any asset for the account?
     *
     * Retained because it proves data is actually flowing, which a registration record does not —
     * an account can be registered while the role is broken. Used to corroborate, not to decide.
     *
     * <p>{@code cloud_account_id} must be the ONLY condition. Pairing it with
     * {@code cloud_provider} returns HTTP 200 and an empty {@code items[]}, which would read as
     * "not connected" for every account forever (the same trap documented on
     * {@link UpwindSyncService#syncInventory}).
     */
    public boolean reportsAssets(String cloudAccountId) {
        if (!apiClient.isConfigured()) return false;
        try {
            List<JsonNode> assets = apiClient.searchInventory(
                    List.of(Map.of("field", "cloud_account_id", "operator", "eq",
                            "value", List.of(cloudAccountId))), 1);
            return !assets.isEmpty();
        } catch (Exception e) {
            log.warn("[Upwind] could not verify assets for account {}: {}",
                    cloudAccountId, e.getMessage());
            return false;
        }
    }

    /**
     * True when Upwind considers the account connected, by either signal.
     *
     * Registration is checked first because it is authoritative and available sooner; assets are
     * the fallback for the case where the integration credential is unset but the data API is
     * working, which is how deployments that predate self-service onboarding look.
     */
    public boolean isLiveInUpwind(String cloudAccountId) {
        return registrationFor(cloudAccountId).map(UpwindIntegrationClient.CloudCredential::connected)
                .orElseGet(() -> reportsAssets(cloudAccountId));
    }

    /**
     * Disconnects one account from XamSecure.
     *
     * Order is deliberate: deregister in Upwind FIRST, then tell the customer to delete their
     * stack. The reverse leaves a window where Upwind is scanning against a role that has just
     * been deleted, which surfaces to them as authorization failures rather than as a clean
     * removal.
     *
     * <p>Deleting the stack alone is NOT a disconnect. Upwind's template Delete handler replies
     * SUCCESS to CloudFormation and calls no API — it even logs "Integration deleted
     * successfully" while doing nothing — so the registration survives and the org accumulates a
     * connection that can never succeed again.
     *
     * <p>Local findings are left in place rather than purged. They are historical fact and every
     * query is account-scoped, so they stay invisible to anyone who no longer owns the account;
     * deleting a customer's security history on a disconnect is not recoverable, whereas leaving
     * it is.
     */
    @Transactional
    public Map<String, Object> disconnect(Long clientId, String cloudAccountId) {
        ownedAccount(clientId, cloudAccountId);

        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElse(null);

        // Resolve the id from Upwind rather than trusting a stored one: connections made before
        // this feature existed (Onfinance, and anything onboarded through the console) have no
        // upwind_credential_id, and those are precisely the ones most likely to need removing.
        String credentialId = conn == null ? null : conn.getUpwindCredentialId();
        if (credentialId == null) {
            credentialId = registrationFor(cloudAccountId)
                    .map(UpwindIntegrationClient.CloudCredential::id).orElse(null);
        }

        boolean deregistered = false;
        String detail;
        if (credentialId == null) {
            // Nothing registered upstream. Still worth clearing local state so the UI stops
            // claiming the account is protected.
            detail = "No active registration found in XamSecure for this account.";
        } else if (!integrationClient.isConfigured()) {
            throw new IllegalStateException(
                    "Cannot disconnect: upwind.onboarding.client-id/secret are not configured, "
                    + "so the registration cannot be removed upstream.");
        } else {
            deregistered = integrationClient.deleteCloudCredential(credentialId);
            detail = deregistered
                    ? "Removed from XamSecure. Now delete the CloudFormation stack in your AWS "
                      + "account to remove the IAM role and stored credential."
                    : "Could not remove the registration upstream; nothing was changed.";
        }

        if (deregistered || credentialId == null) {
            if (conn != null) {
                conn.setStatus("NOT_CONNECTED");
                conn.setUpwindCredentialId(null);
                conn.setConnectionVerifiedAt(null);
                conn.setStackRequestedAt(null);
                conn.setDisconnectedAt(Instant.now());
                conn.setUpdatedAt(Instant.now());
                connectionRepo.save(conn);
            }
            log.info("[Upwind] account {} disconnected from XamSecure (credentialId={})",
                    cloudAccountId, credentialId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn == null ? "NOT_CONNECTED" : conn.getStatus());
        out.put("deregistered", deregistered);
        out.put("stackName", conn == null ? null : conn.getStackName());
        out.put("stackRegion", conn == null ? null : conn.getStackRegion());
        out.put("message", detail);
        return out;
    }

    /**
     * Promotes every PENDING connection that Upwind has started reporting assets for.
     *
     * Only ever moves PENDING to CONNECTED. It cannot demote: Upwind legitimately reports nothing
     * for an account with no scannable resources yet, and a verifier that treated silence as
     * disconnection would flap.
     *
     * @return how many connections were promoted
     */
    /**
     * Adopts every account Upwind reports as connected, creating the local row if absent.
     *
     * Without this, an account connected OUTSIDE XamOps is invisible: {@link
     * #verifyPendingConnections} reads {@code findByStatusNot("CONNECTED")}, which only returns
     * rows that already exist, and {@link XamSecureService#getOnboardingStatus} maps a missing row
     * to NOT_CONNECTED. So every account onboarded through the Upwind console before this feature
     * existed — Xammer's own included — displayed as "Not protected" for ever, with no way to
     * correct it: the UI only offers "Check status" for PENDING rows, so a NOT_CONNECTED account
     * had no button that would re-check it.
     *
     * Upwind's registration list is the authoritative source and is keyed on the AWS account id,
     * so it needs nothing from us to match. Reconciling from it also removes the assumption that
     * XamOps is the only thing that ever creates connections.
     *
     * @return how many rows were created or promoted
     */
    @Transactional
    public int reconcileFromUpwind() {
        if (!integrationClient.isConfigured()) return 0;
        List<UpwindIntegrationClient.CloudCredential> registrations;
        try {
            registrations = integrationClient.listCloudCredentials();
        } catch (Exception e) {
            log.warn("[Upwind] could not list registrations to reconcile: {}", e.getMessage());
            return 0;
        }

        int changed = 0;
        for (UpwindIntegrationClient.CloudCredential reg : registrations) {
            if (reg.cloudAccountId() == null || reg.cloudAccountId().isBlank()) continue;
            if (!reg.connected()) continue;

            UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(reg.cloudAccountId())
                    .orElseGet(() -> {
                        UpwindAccountConnection n = new UpwindAccountConnection();
                        n.setCloudAccountId(reg.cloudAccountId());
                        return n;
                    });
            boolean alreadyCurrent = "CONNECTED".equals(conn.getStatus())
                    && reg.id().equals(conn.getUpwindCredentialId());
            if (alreadyCurrent) continue;

            conn.setStatus("CONNECTED");
            conn.setUpwindCredentialId(reg.id());
            if (conn.getConnectionVerifiedAt() == null) conn.setConnectionVerifiedAt(Instant.now());
            // Upwind still holding a registration means the account IS connected, whatever we
            // recorded earlier — either a disconnect's DELETE failed, or it was re-onboarded.
            // Unlike the asset-based signal, this one cannot lag behind a successful disconnect,
            // because a successful disconnect removes the registration being read here.
            conn.setDisconnectedAt(null);
            conn.setUpdatedAt(Instant.now());
            connectionRepo.save(conn);
            changed++;
            log.info("[Upwind] adopted registration for account {} (credential {})",
                    reg.cloudAccountId(), reg.id());
        }
        return changed;
    }

    @Transactional
    public int verifyPendingConnections() {
        List<UpwindAccountConnection> pending = connectionRepo.findByStatusNot("CONNECTED");
        int promoted = 0;
        for (UpwindAccountConnection conn : pending) {
            if (conn.getCloudAccountId() == null || conn.getCloudAccountId().isBlank()) continue;
            // A connection deliberately torn down must not be resurrected by the poller. Upwind
            // can still be reporting assets for minutes afterwards, and re-promoting would undo a
            // disconnect the customer just performed.
            if (conn.getDisconnectedAt() != null) continue;
            Optional<UpwindIntegrationClient.CloudCredential> reg =
                    registrationFor(conn.getCloudAccountId());
            boolean live = reg.map(UpwindIntegrationClient.CloudCredential::connected)
                    .orElseGet(() -> reportsAssets(conn.getCloudAccountId()));
            if (!live) continue;
            conn.setStatus("CONNECTED");
            conn.setConnectionVerifiedAt(Instant.now());
            reg.ifPresent(c -> conn.setUpwindCredentialId(c.id()));
            conn.setUpdatedAt(Instant.now());
            connectionRepo.save(conn);
            promoted++;
            log.info("[Upwind] account {} is connected in Upwind — marked CONNECTED",
                    conn.getCloudAccountId());
        }
        return promoted;
    }

    /**
     * On-demand check for one account, so the UI's "Check connection" button does not have to
     * wait for the poller.
     */
    @Transactional
    public Map<String, Object> checkConnection(Long clientId, String cloudAccountId) {
        ownedAccount(clientId, cloudAccountId);
        Optional<UpwindIntegrationClient.CloudCredential> reg = registrationFor(cloudAccountId);
        boolean registered = reg.map(UpwindIntegrationClient.CloudCredential::connected).orElse(false);
        boolean assets = reportsAssets(cloudAccountId);
        boolean live = registered || assets;

        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(() -> {
                    UpwindAccountConnection n = new UpwindAccountConnection();
                    n.setCloudAccountId(cloudAccountId);
                    n.setStatus("NOT_CONNECTED");
                    n.setUpdatedAt(Instant.now());
                    return n;
                });
        if (live && !"CONNECTED".equals(conn.getStatus())) {
            conn.setStatus("CONNECTED");
            conn.setConnectionVerifiedAt(Instant.now());
            conn.setDisconnectedAt(null);
            conn.setUpdatedAt(Instant.now());
        }
        reg.ifPresent(c -> conn.setUpwindCredentialId(c.id()));
        connectionRepo.save(conn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cloudAccountId", cloudAccountId);
        out.put("status", conn.getStatus());
        out.put("reportingAssets", assets);
        out.put("registered", registered);
        // Surfaced separately because "registered but the scanner is off" produces no findings and
        // is otherwise indistinguishable from a broken connection.
        out.put("scannerEnabled", reg.map(UpwindIntegrationClient.CloudCredential::cloudScannerEnabled)
                .orElse(null));
        out.put("verifiedAt", conn.getConnectionVerifiedAt());
        out.put("message", message(registered, assets, reg, conn));
        return out;
    }

    private String message(boolean registered, boolean assets,
                           Optional<UpwindIntegrationClient.CloudCredential> reg,
                           UpwindAccountConnection conn) {
        if (registered && assets) return "Connected and reporting data for this account.";
        if (registered && reg.isPresent() && !reg.get().cloudScannerEnabled()) {
            return "Connected, but the cloud scanner is disabled for this account, so no findings "
                    + "will be produced. Contact your XamOps administrator.";
        }
        if (registered) return "Connected. The first scan is still in progress.";
        if (assets) return "Reporting data for this account.";
        return waitingMessage(conn);
    }

    /**
     * What to tell someone whose account is not reporting yet.
     *
     * A stack that deployed successfully registers the account immediately and assets follow
     * within minutes, so a long silence is a failed or abandoned stack rather than slow progress.
     * Saying "still scanning" indefinitely would send people to support instead of to their
     * CloudFormation console, where the actual error is.
     */
    private String waitingMessage(UpwindAccountConnection conn) {
        Instant requested = conn.getStackRequestedAt();
        if (requested == null) {
            return "This account has not been connected yet. Start with Connect account.";
        }
        long minutes = java.time.Duration.between(requested, Instant.now()).toMinutes();
        if (minutes < STACK_GRACE_MINUTES) {
            return "Waiting for the first scan. This usually takes a few minutes after the "
                    + "stack finishes.";
        }
        return "No data yet after " + minutes + " minutes. Check that the CloudFormation stack '"
                + conn.getStackName() + "' in " + conn.getStackRegion()
                + " reached CREATE_COMPLETE — a rolled-back stack leaves no connection behind.";
    }

    /** How long a fresh stack is given before we stop calling it "in progress". */
    static final long STACK_GRACE_MINUTES = 30;

    private CloudAccount ownedAccount(Long clientId, String cloudAccountId) {
        Optional<CloudAccount> found = cloudAccountRepository.findByClientId(clientId).stream()
                .filter(a -> cloudAccountId.equals(a.getProviderAccountId()))
                .findFirst();
        return found.orElseThrow(() -> new AccessDeniedException(
                "Cloud account " + cloudAccountId + " does not belong to this client"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
