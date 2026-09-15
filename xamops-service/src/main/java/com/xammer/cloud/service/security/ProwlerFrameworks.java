package com.xammer.cloud.service.security;

/**
 * The compliance framework identifiers passed to Prowler, per provider.
 *
 * <p>
 * This exists because the lists were duplicated between the manual-trigger path and the weekly
 * scheduler, and they drifted. The scheduler was asking Prowler for {@code pci_3.2.1_gcp},
 * {@code iso27001_2013_gcp}, {@code gdpr_gcp} and {@code nist_csf_1.1_gcp} while
 * {@code GcpSecurityService} — carrying a comment explaining that GCP does not offer the
 * AWS-style names — asked for {@code pci_4.0_gcp} and {@code iso27001_2022_gcp}.
 *
 * <p>
 * That drift is not a cosmetic inconsistency. Prowler validates {@code --compliance} with
 * argparse and rejects the <em>entire</em> argument if any single name is unknown, exiting with
 * code 2 and running no checks at all. So a scheduled scan carrying one wrong name does not
 * produce partial results — it produces none, and the only symptom is an account whose
 * compliance data never updates. Keeping one copy per provider is what stops the two paths from
 * disagreeing again.
 *
 * <p>
 * <strong>Framework names are version-specific to the installed Prowler.</strong> They are not
 * verified at build time and cannot be; the check is
 * {@code prowler <provider> --list-compliance} in the deployment image. If Prowler is upgraded
 * and a framework is renamed, every scan for that provider stops silently, so treat this file as
 * something to re-verify on a Prowler bump.
 */
public final class ProwlerFrameworks {

    /**
     * AWS service groups scanned. Deliberately not the full set — running every service at once
     * exhausts memory on constrained hosts.
     */
    public static final String[] AWS_SERVICES = {
            "s3", "ec2", "iam", "rds", "cloudtrail", "cloudwatch",
            "kms", "lambda", "vpc", "config", "sns", "sqs",
            "efs", "elb", "elbv2", "secretsmanager", "guardduty"
    };

    public static final String[] AWS = {
            "cis_3.0_aws",
            "soc2_aws",
            "pci_3.2.1_aws",
            "nist_csf_1.1_aws",
            "aws_foundational_security_best_practices_aws",
            "iso27001_2013_aws"
    };

    /**
     * GCP frameworks.
     *
     * <p>
     * Note the version numbers differ from the AWS list above and that is correct, not an
     * oversight: Prowler ships {@code pci_4.0_gcp} and {@code iso27001_2022_gcp} for GCP, with no
     * {@code pci_3.2.1_gcp} or {@code iso27001_2013_gcp} equivalent. There is also no
     * {@code gdpr_gcp} or {@code nist_csf_1.1_gcp}, which is why they are absent here despite
     * having AWS counterparts.
     */
    public static final String[] GCP = {
            "cis_2.0_gcp",
            "soc2_gcp",
            "pci_4.0_gcp",
            "iso27001_2022_gcp",
            "mitre_attack_gcp",
            "hipaa_gcp"
    };

    /**
     * Azure frameworks.
     *
     * <p>
     * Same versioning caveat as GCP: Azure has no {@code cis_2.1.1}, {@code pci_3.2.1} or
     * {@code iso27001_2013}.
     */
    public static final String[] AZURE = {
            "cis_3.0_azure",
            "soc2_azure",
            "pci_4.0_azure",
            "iso27001_2022_azure",
            "mitre_attack_azure",
            "hipaa_azure"
    };

    private ProwlerFrameworks() {
    }
}
