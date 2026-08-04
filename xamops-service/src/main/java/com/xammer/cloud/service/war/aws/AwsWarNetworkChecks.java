package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.Scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Network exposure and public-reachability controls (advisory rows 11-27).
 *
 * <p>
 * The advisory's recurring point is that exposed endpoints are found by automated
 * scanners within minutes, not days. These checks look for the specific openings
 * those scanners probe for: SSH and RDP from anywhere, public database endpoints,
 * public Kubernetes API servers, and unauthenticated Lambda URLs.
 */
@Component
public class AwsWarNetworkChecks {

    /** Ports whose exposure to the internet is what gets an account mined. */
    private static final List<Integer> ADMIN_PORTS = List.of(22, 3389);

    private static final Set<String> OPEN_CIDRS = Set.of("0.0.0.0/0", "::/0");

    private final AwsClientProvider clients;

    public AwsWarNetworkChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        for (String region : ctx.regions()) {
            checkSecurityGroups(ctx, region);
            checkVpcPosture(ctx, region);
            checkPublicInstancesAndEips(ctx, region);
            checkPublicDataStores(ctx, region);
            checkEksEndpoints(ctx, region);
            checkLambdaUrlAuth(ctx, region);
            checkSageMakerNetworkIsolation(ctx, region);
            checkEdgeProtection(ctx, region);
        }
    }

    /** AWS-16 (SSH/RDP open) and AWS-18 (deny-by-default posture). */
    private void checkSecurityGroups(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeSecurityGroups",
                List.of("AWS-16", "AWS-18"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            List<SecurityGroup> groups = ec2.describeSecurityGroupsPaginator()
                    .securityGroups().stream().toList();

            List<String> adminOpen = new ArrayList<>();
            List<String> wideOpen = new ArrayList<>();

            for (SecurityGroup sg : groups) {
                for (IpPermission perm : sg.ipPermissions()) {
                    boolean openToWorld = perm.ipRanges().stream()
                            .anyMatch(r -> OPEN_CIDRS.contains(r.cidrIp()))
                            || perm.ipv6Ranges().stream().anyMatch(r -> OPEN_CIDRS.contains(r.cidrIpv6()));
                    if (!openToWorld) {
                        continue;
                    }

                    // A null port range means "all ports" — the widest possible opening.
                    boolean allPorts = perm.fromPort() == null || perm.toPort() == null;
                    if (allPorts) {
                        wideOpen.add(String.format("%s (%s) in %s — all ports open to the internet",
                                sg.groupId(), sg.groupName(), region));
                        continue;
                    }

                    for (int port : ADMIN_PORTS) {
                        if (perm.fromPort() <= port && perm.toPort() >= port) {
                            adminOpen.add(String.format("%s (%s) in %s — port %d open to the internet",
                                    sg.groupId(), sg.groupName(), region, port));
                        }
                    }
                    if (perm.toPort() - perm.fromPort() > 100) {
                        wideOpen.add(String.format("%s (%s) in %s — ports %d-%d open to the internet",
                                sg.groupId(), sg.groupName(), region, perm.fromPort(), perm.toPort()));
                    }
                }
            }

            ctx.merge("AWS-16", WarCheckResult.of(adminOpen,
                            "SSH or RDP is reachable from the internet",
                            "No security group exposes SSH or RDP to 0.0.0.0/0")
                    .withScanned(groups.size()).withRegion(region));

            ctx.merge("AWS-18", WarCheckResult.of(wideOpen,
                            "Security groups allow broad inbound ranges from the internet",
                            "Inbound rules are scoped rather than wide open")
                    .withScanned(groups.size()).withRegion(region));
        });
    }

    /** AWS-11 (VPC endpoints), AWS-12 (flow logs), AWS-13 (default VPC in use). */
    private void checkVpcPosture(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeVpcs",
                List.of("AWS-11", "AWS-12", "AWS-13"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            List<Vpc> vpcs = ec2.describeVpcs().vpcs();
            if (vpcs.isEmpty()) {
                return;
            }

            Set<String> vpcsWithFlowLogs = new HashSet<>();
            ctx.quiet(null, () -> {
                ec2.describeFlowLogsPaginator().flowLogs()
                        .forEach(fl -> vpcsWithFlowLogs.add(fl.resourceId()));
                return null;
            });

            List<String> missingFlowLogs = new ArrayList<>();
            List<String> defaultVpcsInUse = new ArrayList<>();

            for (Vpc vpc : vpcs) {
                if (!vpcsWithFlowLogs.contains(vpc.vpcId())) {
                    missingFlowLogs.add(vpc.vpcId() + " in " + region + " has no flow logs");
                }
                if (Boolean.TRUE.equals(vpc.isDefault())) {
                    // A default VPC that holds nothing is untidy, not dangerous. One with
                    // running instances means workloads sit on open default rules.
                    long running = ctx.quiet(0L, () -> ec2.describeInstances(r -> r.filters(List.of(
                            Filter.builder().name("vpc-id").values(vpc.vpcId()).build(),
                            Filter.builder().name("instance-state-name").values("running").build())))
                            .reservations().stream().mapToLong(res -> res.instances().size()).sum());
                    if (running > 0) {
                        defaultVpcsInUse.add(String.format(
                                "%s in %s is the default VPC and runs %d instance(s)",
                                vpc.vpcId(), region, running));
                    }
                }
            }

            int endpoints = ctx.quiet(0, () -> ec2.describeVpcEndpoints().vpcEndpoints().size());

            ctx.merge("AWS-12", WarCheckResult.of(missingFlowLogs,
                            "VPCs are running without flow logs — no network forensics are possible",
                            "Every VPC has flow logs enabled")
                    .withScanned(vpcs.size()).withRegion(region));

            ctx.merge("AWS-13", WarCheckResult.of(defaultVpcsInUse,
                    "Production workloads are running in a default VPC",
                    "No workloads are running in a default VPC").withRegion(region));

            ctx.merge("AWS-11", endpoints > 0
                    ? WarCheckResult.pass(endpoints + " VPC endpoint(s) keep AWS API traffic off the "
                            + "public internet")
                    : WarCheckResult.fail("No VPC endpoints in " + region + " — service traffic and any "
                            + "exfiltration path traverse the public internet").withRegion(region));
        });
    }

    /** AWS-21 (public IPs on EC2) and AWS-25 (unattached Elastic IPs). */
    private void checkPublicInstancesAndEips(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeInstances",
                List.of("AWS-21", "AWS-25"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);

            List<String> publicInstances = new ArrayList<>();
            int scanned = 0;
            for (var reservation : ec2.describeInstancesPaginator(r -> r.filters(List.of(
                    Filter.builder().name("instance-state-name").values("running").build())))
                    .reservations()) {
                for (Instance i : reservation.instances()) {
                    scanned++;
                    if (i.publicIpAddress() != null) {
                        publicInstances.add(String.format("%s (%s) in %s has public IP %s",
                                i.instanceId(), i.instanceType(), region, i.publicIpAddress()));
                    }
                }
            }

            List<String> idleEips = ctx.quiet(List.<String>of(), () -> ec2.describeAddresses().addresses()
                    .stream()
                    .filter(a -> a.instanceId() == null && a.networkInterfaceId() == null)
                    .map(a -> a.publicIp() + " in " + region + " is allocated but unattached")
                    .toList());

            ctx.merge("AWS-21", WarCheckResult.of(publicInstances,
                            "EC2 instances hold public IPs and are directly reachable",
                            "No running instance has a public IP")
                    .withScanned(scanned).withRegion(region));

            ctx.merge("AWS-25", WarCheckResult.of(idleEips,
                    "Unattached Elastic IPs are allocated — ungoverned surface that also bills",
                    "No unattached Elastic IPs").withRegion(region));
        });
    }

    /** AWS-23: RDS, Aurora and Redshift must not be publicly accessible. */
    private void checkPublicDataStores(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "rds:DescribeDBInstances",
                List.of("AWS-23"), () -> {
            RdsClient rds = clients.getRdsClient(ctx.account(), region);
            List<String> exposed = new ArrayList<>();
            int scanned = 0;

            for (var db : rds.describeDBInstancesPaginator().dbInstances()) {
                scanned++;
                if (Boolean.TRUE.equals(db.publiclyAccessible())) {
                    exposed.add(String.format("RDS %s (%s) in %s is publicly accessible",
                            db.dbInstanceIdentifier(), db.engine(), region));
                }
            }

            int redshiftScanned = 0;
            try {
                RedshiftClient redshift = clients.getRedshiftClient(ctx.account(), region);
                for (var cluster : redshift.describeClustersPaginator().clusters()) {
                    redshiftScanned++;
                    if (Boolean.TRUE.equals(cluster.publiclyAccessible())) {
                        exposed.add(String.format("Redshift %s in %s is publicly accessible",
                                cluster.clusterIdentifier(), region));
                    }
                }
            } catch (Exception e) {
                // Redshift may not be enabled in the region; RDS verdict still stands.
            }

            ctx.merge("AWS-23", WarCheckResult.of(exposed,
                            "Managed databases are reachable from the internet",
                            "No RDS, Aurora or Redshift endpoint is publicly accessible")
                    .withScanned(scanned + redshiftScanned).withRegion(region));
        });
    }

    /** AWS-22 and AWS-54: the EKS API server is a direct route to a compute fleet. */
    private void checkEksEndpoints(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "eks:DescribeCluster",
                List.of("AWS-22", "AWS-54"), () -> {
            EksClient eks = clients.getEksClient(ctx.account(), region);
            List<String> clusterNames = eks.listClustersPaginator().clusters().stream().toList();
            if (clusterNames.isEmpty()) {
                ctx.merge("AWS-22", WarCheckResult.notApplicable("No EKS clusters in " + region));
                ctx.merge("AWS-54", WarCheckResult.notApplicable("No EKS clusters in " + region));
                return;
            }

            List<String> publicEndpoints = new ArrayList<>();
            List<String> restrictedPublic = new ArrayList<>();

            for (String name : clusterNames) {
                var cluster = eks.describeCluster(r -> r.name(name)).cluster();
                var vpcConfig = cluster.resourcesVpcConfig();
                if (vpcConfig == null || !Boolean.TRUE.equals(vpcConfig.endpointPublicAccess())) {
                    continue;
                }
                List<String> cidrs = vpcConfig.publicAccessCidrs();
                boolean openToWorld = cidrs == null || cidrs.isEmpty()
                        || cidrs.stream().anyMatch(OPEN_CIDRS::contains);
                if (openToWorld) {
                    publicEndpoints.add(String.format(
                            "Cluster %s in %s exposes its Kubernetes API to 0.0.0.0/0", name, region));
                } else {
                    restrictedPublic.add(String.format(
                            "Cluster %s in %s is public but restricted to %s", name, region, cidrs));
                }
            }

            ctx.merge("AWS-22", WarCheckResult.of(publicEndpoints,
                            "EKS clusters expose a public API endpoint to the whole internet",
                            "No EKS cluster is publicly reachable without a CIDR allow-list")
                    .withScanned(clusterNames.size()).withRegion(region));

            if (!publicEndpoints.isEmpty()) {
                ctx.merge("AWS-54", WarCheckResult.of(publicEndpoints,
                        "The EKS API server is reachable from the open internet",
                        "").withRegion(region));
            } else if (!restrictedPublic.isEmpty()) {
                ctx.merge("AWS-54", WarCheckResult.warn(
                                "EKS endpoints are public but CIDR-restricted — private access is stronger")
                        .withEvidence(restrictedPublic).withRegion(region));
            } else {
                ctx.merge("AWS-54", WarCheckResult.pass("All EKS endpoints are private"));
            }
        });
    }

    /** AWS-27: a Lambda function URL with auth NONE is a billable open endpoint. */
    private void checkLambdaUrlAuth(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "lambda:ListFunctionUrlConfigs",
                List.of("AWS-27"), () -> {
            LambdaClient lambda = clients.getLambdaClient(ctx.account(), region);
            List<String> open = new ArrayList<>();
            int scanned = 0;

            for (var fn : lambda.listFunctionsPaginator().functions()) {
                scanned++;
                var configs = ctx.quiet(List.<software.amazon.awssdk.services.lambda.model
                        .FunctionUrlConfig>of(), () -> lambda
                        .listFunctionUrlConfigs(r -> r.functionName(fn.functionName()))
                        .functionUrlConfigs());
                for (var cfg : configs) {
                    if ("NONE".equalsIgnoreCase(cfg.authTypeAsString())) {
                        open.add(String.format("%s in %s has a function URL with auth type NONE",
                                fn.functionName(), region));
                    }
                }
            }

            ctx.merge("AWS-27", WarCheckResult.of(open,
                            "Lambda function URLs accept unauthenticated, billable invocations",
                            "All Lambda function URLs require IAM authentication")
                    .withScanned(scanned).withRegion(region));
        });
    }

    /** AWS-26: SageMaker notebooks with direct internet access. */
    private void checkSageMakerNetworkIsolation(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "sagemaker:DescribeNotebookInstance",
                List.of("AWS-26"), () -> {
            SageMakerClient sm = clients.getSageMakerClient(ctx.account(), region);
            var notebooks = sm.listNotebookInstances().notebookInstances();
            if (notebooks.isEmpty()) {
                ctx.merge("AWS-26", WarCheckResult.notApplicable(
                        "No SageMaker notebook instances in " + region));
                return;
            }

            List<String> exposed = new ArrayList<>();
            for (var nb : notebooks) {
                var detail = sm.describeNotebookInstance(r -> r
                        .notebookInstanceName(nb.notebookInstanceName()));
                boolean directInternet = detail.directInternetAccessAsString() == null
                        || "Enabled".equalsIgnoreCase(detail.directInternetAccessAsString());
                boolean noVpc = detail.subnetId() == null || detail.subnetId().isBlank();
                if (directInternet || noVpc) {
                    exposed.add(String.format("Notebook %s in %s has direct internet access%s",
                            nb.notebookInstanceName(), region, noVpc ? " and is not VPC-attached" : ""));
                }
            }

            ctx.merge("AWS-26", WarCheckResult.of(exposed,
                            "SageMaker notebooks have direct internet access",
                            "All SageMaker notebooks run in VPC-only mode")
                    .withScanned(notebooks.size()).withRegion(region));
        });
    }

    /** AWS-20: WAF in front of internet-facing endpoints. */
    private void checkEdgeProtection(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "wafv2:ListWebACLs",
                List.of("AWS-20"), () -> {
            Wafv2Client waf = clients.getWafv2Client(ctx.account(), region);
            int regional = ctx.quiet(0, () -> waf
                    .listWebACLs(r -> r.scope(Scope.REGIONAL)).webACLs().size());

            ctx.merge("AWS-20", regional > 0
                    ? WarCheckResult.pass(regional + " regional web ACL(s) protect edge endpoints in " + region)
                    : WarCheckResult.fail("No WAF web ACL in " + region + " — attack traffic reaching an "
                            + "Auto Scaling target becomes a compute billing spike").withRegion(region));
        });
    }
}
