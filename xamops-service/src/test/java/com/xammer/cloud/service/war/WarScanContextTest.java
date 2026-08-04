package com.xammer.cloud.service.war;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.dto.war.WarStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the failure-classification rules.
 *
 * <p>
 * These exist because WAR shipped a bug that told a customer "34 permissions missing"
 * when their credentials had simply expired — sending them to rewrite an IAM policy
 * that was never the problem. The distinction between "denied" and "never got far
 * enough to be denied" is the whole point of these assertions.
 */
class WarScanContextTest {

    private static final String TOKEN_ERROR = "The security token included in the request is invalid.";
    private static final String DENIED_ERROR =
            "User: arn:aws:sts::1:assumed-role/x is not authorized to perform: guardduty:ListDetectors";

    private WarScanContext context() {
        CloudAccount account = new CloudAccount();
        account.setProvider("AWS");
        account.setAwsAccountId("605134457560");
        account.setAccountName("test");
        return new WarScanContext(account, List.of("ap-south-1", "us-east-1"));
    }

    @Test
    void accountId_resolvesPerProvider() {
        CloudAccount aws = new CloudAccount();
        aws.setProvider("AWS");
        aws.setAwsAccountId("605134457560");
        assertThat(new WarScanContext(aws, List.of()).accountId()).isEqualTo("605134457560");

        // Regression: this returned getAwsAccountId() for every provider, which is null
        // on a GCP account. Passing null to the client provider made every GCP check look
        // up credentials for "no project", matching every row with a null project id and
        // failing with a non-unique-result error that looked like a data problem.
        CloudAccount gcp = new CloudAccount();
        gcp.setProvider("GCP");
        gcp.setGcpProjectId("xamops");
        assertThat(new WarScanContext(gcp, List.of()).accountId()).isEqualTo("xamops");

        CloudAccount azure = new CloudAccount();
        azure.setProvider("Azure");
        azure.setAzureSubscriptionId("sub-123");
        assertThat(new WarScanContext(azure, List.of()).accountId()).isEqualTo("sub-123");
    }

    @Test
    void authorizationFailure_isReportedAsAMissingPermission() {
        WarScanContext ctx = context();

        ctx.guard("guardduty:ListDetectors", List.of("AWS-42"), () -> {
            throw new RuntimeException(DENIED_ERROR);
        });

        assertThat(ctx.deniedActions()).containsExactly("guardduty:ListDetectors");
        assertThat(ctx.blockedActions()).isEmpty();
    }

    @Test
    void expiredCredentials_areNotReportedAsMissingPermissions() {
        WarScanContext ctx = context();

        ctx.guard("iam:ListUsers", List.of("AWS-4"), () -> {
            throw new RuntimeException(TOKEN_ERROR);
        });

        // The remedy is refreshing credentials, not granting iam:ListUsers.
        assertThat(ctx.deniedActions()).isEmpty();
        assertThat(ctx.blockedActions()).containsExactly("iam:ListUsers");
    }

    @Test
    void blockedControl_carriesTheReasonRatherThanAGenericMessage() {
        WarScanContext ctx = context();

        ctx.guard("iam:ListUsers", List.of("AWS-4"), () -> {
            throw new RuntimeException(TOKEN_ERROR);
        });

        WarCheckResult result = ctx.results().get("AWS-4");
        assertThat(result.getStatus()).isEqualTo(WarStatus.UNKNOWN);
        assertThat(result.getSummary()).contains("security token");
    }

    @Test
    void regionalFailure_recordsAReasonForTheControlsItOwns() {
        WarScanContext ctx = context();

        ctx.guardRegion("ap-south-1", "ec2:DescribeSecurityGroups",
                List.of("AWS-16", "AWS-18"), () -> {
                    throw new RuntimeException(TOKEN_ERROR);
                });

        assertThat(ctx.blockedReasons()).containsKeys("AWS-16", "AWS-18");
        assertThat(ctx.blockedReasons().get("AWS-16")).contains("security token");
        assertThat(ctx.warnings()).hasSize(1);
    }

    @Test
    void dominantFailure_namesTheSingleSharedCause() {
        WarScanContext ctx = context();

        for (String action : List.of("iam:ListUsers", "ec2:DescribeVpcs", "s3:ListAllMyBuckets")) {
            ctx.guard(action, List.of("AWS-1"), () -> {
                throw new RuntimeException(TOKEN_ERROR);
            });
        }

        assertThat(ctx.dominantFailure()).contains(TOKEN_ERROR);
    }

    @Test
    void dominantFailure_isAbsentWhenCausesAreMixed() {
        WarScanContext ctx = context();

        ctx.guard("a:one", List.of("AWS-1"), () -> {
            throw new RuntimeException("first cause");
        });
        ctx.guard("b:two", List.of("AWS-2"), () -> {
            throw new RuntimeException("second cause");
        });
        ctx.guard("c:three", List.of("AWS-3"), () -> {
            throw new RuntimeException("third cause");
        });

        // No single cause accounts for a majority, so there is nothing to headline.
        assertThat(ctx.dominantFailure()).isEmpty();
    }

    @Test
    void successfulRegion_stillWinsOverAFailedOne() {
        WarScanContext ctx = context();

        ctx.guardRegion("ap-south-1", "ec2:DescribeSecurityGroups", List.of("AWS-16"),
                () -> ctx.merge("AWS-16", WarCheckResult.pass("clean in ap-south-1")));
        ctx.guardRegion("us-east-1", "ec2:DescribeSecurityGroups", List.of("AWS-16"), () -> {
            throw new RuntimeException(TOKEN_ERROR);
        });

        // A verdict from one region must not be downgraded to UNKNOWN by another's failure.
        assertThat(ctx.results().get("AWS-16").getStatus()).isEqualTo(WarStatus.PASS);
    }

    @Test
    void merge_takesTheWorstStatusAndKeepsAllEvidence() {
        WarScanContext ctx = context();

        ctx.merge("AWS-16", WarCheckResult.pass("clean in ap-south-1"));
        ctx.merge("AWS-16", WarCheckResult.fail("port 22 open")
                .withEvidence("sg-abc in us-east-1 — port 22 open to the internet"));

        WarCheckResult result = ctx.results().get("AWS-16");
        // One region with an open port makes the control fail; an attacker needs only one.
        assertThat(result.getStatus()).isEqualTo(WarStatus.FAIL);
        assertThat(result.getEvidence()).hasSize(1);
        assertThat(result.getAffectedCount()).isEqualTo(1);
    }
}
