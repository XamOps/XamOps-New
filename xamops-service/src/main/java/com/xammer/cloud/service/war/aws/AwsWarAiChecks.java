package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.PolicyType;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * AI service controls (advisory rows 64-78, the automatable subset).
 *
 * <p>
 * AI services invert the usual cost model: there is no instance to notice, just
 * per-token billing that scales with whoever holds the credential. The advisory's
 * point is that a leaked key against Bedrock produces a spike with no compute
 * footprint to spot, so the controls that matter are IAM scoping, a network
 * perimeter, and invocation logging to reconstruct what happened.
 */
@Component
public class AwsWarAiChecks {

    private final AwsClientProvider clients;

    public AwsWarAiChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        checkBedrockIamScope(ctx);
        checkRegionRestriction(ctx);
        for (String region : ctx.regions()) {
            checkBedrockLoggingAndGuardrails(ctx, region);
            checkBedrockPrivateAccess(ctx, region);
            checkSageMakerEncryption(ctx, region);
        }
    }

    /** AWS-64: wildcard Bedrock permissions in customer-managed policies. */
    private void checkBedrockIamScope(WarScanContext ctx) {
        ctx.guard("iam:GetPolicyVersion", List.of("AWS-64"), () -> {
            IamClient iam = clients.getIamClient(ctx.account());
            List<String> wildcards = new ArrayList<>();
            int inspected = 0;

            for (var policy : iam.listPoliciesPaginator(r -> r.scope("Local")).policies()) {
                if (policy.defaultVersionId() == null) {
                    continue;
                }
                inspected++;
                String document = ctx.quiet("", () -> {
                    String raw = iam.getPolicyVersion(r -> r
                            .policyArn(policy.arn())
                            .versionId(policy.defaultVersionId()))
                            .policyVersion().document();
                    return raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
                });

                if (document.contains("bedrock:*")) {
                    wildcards.add(policy.policyName() + " grants bedrock:* — any model, any Region");
                } else if (document.contains("\"bedrock:InvokeModel\"")
                        && document.contains("\"Resource\": \"*\"")) {
                    wildcards.add(policy.policyName() + " allows bedrock:InvokeModel on Resource \"*\" — "
                            + "not scoped to approved foundation model ARNs");
                }
            }

            if (inspected == 0) {
                ctx.put("AWS-64", WarCheckResult.notApplicable(
                        "No customer-managed IAM policies to inspect"));
                return;
            }
            ctx.put("AWS-64", WarCheckResult.of(wildcards,
                            "Policies grant wildcard Bedrock permissions",
                            "No customer-managed policy grants bedrock:*")
                    .withScanned(inspected));
        });
    }

    /** AWS-69: an SCP restricting which Regions and models can be invoked. */
    private void checkRegionRestriction(WarScanContext ctx) {
        ctx.guard("organizations:ListPolicies", List.of("AWS-69"), () -> {
            OrganizationsClient org = clients.getOrganizationsClient(ctx.account());
            var policies = org.listPolicies(r -> r.filter(PolicyType.SERVICE_CONTROL_POLICY)).policies();

            boolean restricts = policies.stream().anyMatch(p -> {
                String content = ctx.quiet("", () -> org
                        .describePolicy(r -> r.policyId(p.id())).policy().content());
                return content.contains("aws:RequestedRegion") || content.contains("bedrock:");
            });

            ctx.put("AWS-69", restricts
                    ? WarCheckResult.pass("An SCP constrains Region or Bedrock model access")
                    : WarCheckResult.fail("No SCP restricts which Regions or foundation models can be "
                            + "invoked — shadow AI usage is unbounded"));
        });
    }

    /** AWS-66 (invocation logging) and AWS-68 (guardrails). */
    private void checkBedrockLoggingAndGuardrails(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "bedrock:GetModelInvocationLoggingConfiguration",
                List.of("AWS-66", "AWS-68"), () -> {
            BedrockClient bedrock = clients.getBedrockClient(ctx.account(), region);

            var logging = bedrock.getModelInvocationLoggingConfiguration(r -> {
            }).loggingConfig();
            boolean logged = logging != null
                    && (logging.s3Config() != null || logging.cloudWatchConfig() != null);

            ctx.merge("AWS-66", logged
                    ? WarCheckResult.pass("Bedrock model invocation logging is on in " + region)
                    : WarCheckResult.fail("Bedrock invocation logging is off in " + region + " — a runaway "
                            + "agent loop or token-abuse spike cannot be traced to its prompts")
                            .withRegion(region));

            int guardrails = ctx.quiet(0, () -> bedrock.listGuardrails(r -> {
            }).guardrails().size());
            ctx.merge("AWS-68", guardrails > 0
                    ? WarCheckResult.pass(guardrails + " Bedrock guardrail(s) configured in " + region)
                    : WarCheckResult.fail("No Bedrock guardrails in " + region + " — nothing limits "
                            + "adversarial prompts designed to inflate token consumption")
                            .withRegion(region));
        });
    }

    /** AWS-65: Bedrock reached over PrivateLink rather than the public API endpoint. */
    private void checkBedrockPrivateAccess(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "ec2:DescribeVpcEndpoints",
                List.of("AWS-65"), () -> {
            Ec2Client ec2 = clients.getEc2Client(ctx.account(), region);
            boolean bedrockEndpoint = ec2.describeVpcEndpoints().vpcEndpoints().stream()
                    .anyMatch(e -> e.serviceName() != null && e.serviceName().contains("bedrock"));

            ctx.merge("AWS-65", bedrockEndpoint
                    ? WarCheckResult.pass("A Bedrock VPC endpoint keeps model traffic off the internet")
                    : WarCheckResult.fail("No Bedrock VPC endpoint in " + region + " — a leaked key works "
                            + "from anywhere on the internet").withRegion(region));
        });
    }

    /** AWS-67 and AWS-74: KMS on SageMaker notebooks and the Feature Store. */
    private void checkSageMakerEncryption(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "sagemaker:ListNotebookInstances",
                List.of("AWS-67", "AWS-74"), () -> {
            SageMakerClient sm = clients.getSageMakerClient(ctx.account(), region);

            var notebooks = sm.listNotebookInstances().notebookInstances();
            List<String> unencrypted = new ArrayList<>();
            for (var nb : notebooks) {
                var detail = sm.describeNotebookInstance(r -> r
                        .notebookInstanceName(nb.notebookInstanceName()));
                if (detail.kmsKeyId() == null || detail.kmsKeyId().isBlank()) {
                    unencrypted.add(String.format("Notebook %s in %s has no customer managed KMS key",
                            nb.notebookInstanceName(), region));
                }
            }

            var featureGroups = ctx.quiet(List.<software.amazon.awssdk.services.sagemaker.model
                    .FeatureGroupSummary>of(), () -> sm.listFeatureGroups(r -> {
                    }).featureGroupSummaries());
            List<String> featureStoreGaps = new ArrayList<>();
            for (var group : featureGroups) {
                var detail = ctx.quiet(null, () -> sm.describeFeatureGroup(r -> r
                        .featureGroupName(group.featureGroupName())));
                if (detail == null) {
                    continue;
                }
                boolean online = detail.onlineStoreConfig() != null
                        && detail.onlineStoreConfig().securityConfig() != null
                        && detail.onlineStoreConfig().securityConfig().kmsKeyId() != null;
                boolean offline = detail.offlineStoreConfig() != null
                        && detail.offlineStoreConfig().s3StorageConfig() != null
                        && detail.offlineStoreConfig().s3StorageConfig().kmsKeyId() != null;
                if (!online && !offline) {
                    featureStoreGaps.add(String.format("Feature group %s in %s has no KMS key on either "
                            + "store", group.featureGroupName(), region));
                }
            }

            if (notebooks.isEmpty()) {
                ctx.merge("AWS-67", WarCheckResult.notApplicable(
                        "No SageMaker notebooks in " + region));
            } else {
                ctx.merge("AWS-67", WarCheckResult.of(unencrypted,
                                "SageMaker artifacts are not encrypted with customer managed keys",
                                "SageMaker notebooks use customer managed KMS keys")
                        .withScanned(notebooks.size()).withRegion(region));
            }

            if (featureGroups.isEmpty()) {
                ctx.merge("AWS-74", WarCheckResult.notApplicable(
                        "No SageMaker feature groups in " + region));
            } else {
                ctx.merge("AWS-74", WarCheckResult.of(featureStoreGaps,
                                "Feature Store data is not encrypted with a customer managed key",
                                "Feature Store online and offline stores are KMS encrypted")
                        .withScanned(featureGroups.size()).withRegion(region));
            }
        });
    }
}
