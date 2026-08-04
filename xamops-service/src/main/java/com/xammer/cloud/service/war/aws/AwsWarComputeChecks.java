package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.inspector2.Inspector2Client;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compute, container and patching controls (advisory rows 46-57).
 *
 * <p>
 * Once an attacker has any foothold, this group decides whether they get one
 * instance or the whole fleet. IMDSv1 in particular turns a single SSRF bug into
 * stolen instance-role credentials, which is the documented path in several real
 * AWS breaches — hence the advisory rating it Critical.
 */
@Component
public class AwsWarComputeChecks {

    private final AwsClientProvider clients;

    public AwsWarComputeChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        for (String region : ctx.regions()) {
            checkInstanceHardening(ctx, region);
            checkPatchManagement(ctx, region);
            checkInspector(ctx, region);
            checkEksHardening(ctx, region);
            checkEcrScanning(ctx, region);
        }
    }

    /** AWS-46 (IMDSv2) and AWS-49 (SSH key reliance). */
    private void checkInstanceHardening(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeInstances",
                List.of("AWS-46", "AWS-49"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);

            List<String> imdsV1 = new ArrayList<>();
            List<String> sshKeyed = new ArrayList<>();
            int scanned = 0;

            for (var reservation : ec2.describeInstancesPaginator(r -> r.filters(List.of(
                    Filter.builder().name("instance-state-name").values("running").build())))
                    .reservations()) {
                for (Instance i : reservation.instances()) {
                    scanned++;
                    var metadata = i.metadataOptions();
                    if (metadata == null || !"required".equalsIgnoreCase(metadata.httpTokensAsString())) {
                        imdsV1.add(String.format("%s (%s) in %s still accepts IMDSv1 — an SSRF bug here "
                                        + "hands over the instance role credentials",
                                i.instanceId(), i.instanceType(), region));
                    }
                    if (i.keyName() != null && !i.keyName().isBlank()) {
                        sshKeyed.add(String.format("%s in %s is launched with SSH key pair %s",
                                i.instanceId(), region, i.keyName()));
                    }
                }
            }

            if (scanned == 0) {
                ctx.merge("AWS-46", WarCheckResult.notApplicable("No running instances in " + region));
                ctx.merge("AWS-49", WarCheckResult.notApplicable("No running instances in " + region));
                return;
            }

            ctx.merge("AWS-46", WarCheckResult.of(imdsV1,
                            "Instances still allow IMDSv1 token-less metadata access",
                            "Every running instance requires IMDSv2")
                    .withScanned(scanned).withRegion(region));

            // SSH keys are not automatically wrong, but Session Manager removes the
            // shared-secret and open-port problem entirely, so this is a warning.
            ctx.merge("AWS-49", sshKeyed.isEmpty()
                    ? WarCheckResult.pass("No instance depends on an SSH key pair")
                    : WarCheckResult.warn("Instances rely on SSH key pairs rather than Session Manager")
                            .withEvidence(sshKeyed).withScanned(scanned).withRegion(region));
        });
    }

    /** AWS-48: unpatched instances, and instances SSM cannot even see. */
    private void checkPatchManagement(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ssm:DescribeInstanceInformation",
                List.of("AWS-48"), () -> {
            SsmClient ssm = clients.getSsmClient(ctx.account(), region);
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);

            long runningInstances = ctx.quiet(0L, () -> ec2.describeInstancesPaginator(r -> r.filters(
                    List.of(Filter.builder().name("instance-state-name").values("running").build())))
                    .reservations().stream().mapToLong(res -> res.instances().size()).sum());

            if (runningInstances == 0) {
                ctx.merge("AWS-48", WarCheckResult.notApplicable("No running instances in " + region));
                return;
            }

            long managed = ssm.describeInstanceInformationPaginator().instanceInformationList()
                    .stream().filter(i -> "Online".equalsIgnoreCase(i.pingStatusAsString())).count();

            List<String> evidence = new ArrayList<>();
            if (managed < runningInstances) {
                evidence.add(String.format("%d of %d running instances in %s are not managed by SSM, so "
                                + "Patch Manager cannot reach them",
                        runningInstances - managed, runningInstances, region));
            }

            ctx.quiet(null, () -> {
                ssm.listComplianceSummaries(r -> {
                }).complianceSummaryItems().stream()
                        .filter(s -> "Patch".equalsIgnoreCase(s.complianceType()))
                        .forEach(s -> {
                            int nonCompliant = s.nonCompliantSummary() == null ? 0
                                    : s.nonCompliantSummary().nonCompliantCount();
                            if (nonCompliant > 0) {
                                evidence.add(String.format("%d resources in %s are patch non-compliant",
                                        nonCompliant, region));
                            }
                        });
                return null;
            });

            ctx.merge("AWS-48", WarCheckResult.of(evidence,
                    "Patching is not under automated management",
                    "All running instances are SSM-managed and patch compliant").withRegion(region));
        });
    }

    /** AWS-50: continuous vulnerability scanning. */
    private void checkInspector(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "inspector2:BatchGetAccountStatus",
                List.of("AWS-50"), () -> {
            Inspector2Client inspector = clients.getInspector2Client(ctx.account(), region);
            var statuses = inspector.batchGetAccountStatus(r -> {
            }).accounts();

            if (statuses.isEmpty()) {
                ctx.merge("AWS-50", WarCheckResult.fail(
                        "Amazon Inspector is not enabled in " + region).withRegion(region));
                return;
            }

            List<String> disabled = new ArrayList<>();
            for (var status : statuses) {
                String accountState = status.state() == null ? null : status.state().statusAsString();
                if (!"ENABLED".equalsIgnoreCase(accountState)) {
                    disabled.add(region + " — Inspector account state is "
                            + (accountState == null ? "unknown" : accountState));
                }
                var resources = status.resourceState();
                if (resources != null) {
                    if (resources.ec2() != null && !"ENABLED".equalsIgnoreCase(
                            resources.ec2().statusAsString())) {
                        disabled.add(region + " — EC2 scanning is " + resources.ec2().statusAsString());
                    }
                    if (resources.ecr() != null && !"ENABLED".equalsIgnoreCase(
                            resources.ecr().statusAsString())) {
                        disabled.add(region + " — ECR scanning is " + resources.ecr().statusAsString());
                    }
                }
            }

            ctx.merge("AWS-50", WarCheckResult.of(disabled,
                    "Inspector vulnerability scanning is not fully enabled",
                    "Inspector is scanning compute and container images").withRegion(region));
        });
    }

    /** AWS-52 (IRSA), AWS-53 (control plane logging), AWS-57 (managed node groups). */
    private void checkEksHardening(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "eks:DescribeCluster",
                List.of("AWS-52", "AWS-53", "AWS-57"), () -> {
            EksClient eks = clients.getEksClient(ctx.account(), region);
            List<String> names = eks.listClustersPaginator().clusters().stream().toList();

            if (names.isEmpty()) {
                List.of("AWS-52", "AWS-53", "AWS-57").forEach(id -> ctx.merge(id,
                        WarCheckResult.notApplicable("No EKS clusters in " + region)));
                return;
            }

            List<String> noOidc = new ArrayList<>();
            List<String> loggingGaps = new ArrayList<>();
            List<String> selfManaged = new ArrayList<>();

            for (String name : names) {
                var cluster = eks.describeCluster(r -> r.name(name)).cluster();

                boolean oidc = cluster.identity() != null && cluster.identity().oidc() != null
                        && cluster.identity().oidc().issuer() != null;
                if (!oidc) {
                    noOidc.add(String.format("Cluster %s in %s has no OIDC provider, so pods cannot use "
                            + "IRSA and must borrow the node role", name, region));
                }

                var logging = cluster.logging();
                boolean auditOn = logging != null && logging.clusterLogging() != null
                        && logging.clusterLogging().stream().anyMatch(l -> Boolean.TRUE.equals(l.enabled())
                                && l.typesAsStrings() != null
                                && l.typesAsStrings().stream()
                                        .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains("audit")));
                if (!auditOn) {
                    loggingGaps.add(String.format("Cluster %s in %s has audit logging disabled", name, region));
                }

                int managedGroups = ctx.quiet(0, () -> eks
                        .listNodegroups(r -> r.clusterName(name)).nodegroups().size());
                boolean fargate = ctx.quiet(false, () -> !eks
                        .listFargateProfiles(r -> r.clusterName(name)).fargateProfileNames().isEmpty());
                if (managedGroups == 0 && !fargate) {
                    selfManaged.add(String.format("Cluster %s in %s has no managed node group or Fargate "
                            + "profile — node patching is manual", name, region));
                }
            }

            ctx.merge("AWS-52", WarCheckResult.of(noOidc,
                    "EKS clusters cannot use IRSA, so pods inherit broad node permissions",
                    "All clusters have an OIDC provider for IRSA").withRegion(region));

            ctx.merge("AWS-53", WarCheckResult.of(loggingGaps,
                    "EKS control plane audit logging is off",
                    "All clusters log control plane audit events").withRegion(region));

            ctx.merge("AWS-57", selfManaged.isEmpty()
                    ? WarCheckResult.pass("Clusters use managed node groups or Fargate")
                    : WarCheckResult.warn("Clusters depend on self-managed nodes")
                            .withEvidence(selfManaged).withRegion(region));
        });
    }

    /** AWS-56: ECR scanning on push. */
    private void checkEcrScanning(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ecr:DescribeRepositories",
                List.of("AWS-56"), () -> {
            EcrClient ecr = clients.getEcrClient(ctx.account(), region);
            var repos = ecr.describeRepositoriesPaginator().repositories().stream().toList();

            if (repos.isEmpty()) {
                ctx.merge("AWS-56", WarCheckResult.notApplicable("No ECR repositories in " + region));
                return;
            }

            List<String> unscanned = repos.stream()
                    .filter(r -> r.imageScanningConfiguration() == null
                            || !Boolean.TRUE.equals(r.imageScanningConfiguration().scanOnPush()))
                    .map(r -> String.format("Repository %s in %s does not scan on push",
                            r.repositoryName(), region))
                    .toList();

            ctx.merge("AWS-56", WarCheckResult.of(unscanned,
                            "Container images are pushed without vulnerability scanning",
                            "All repositories scan images on push")
                    .withScanned(repos.size()).withRegion(region));
        });
    }
}
