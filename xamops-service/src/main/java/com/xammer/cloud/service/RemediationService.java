package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import org.springframework.stereotype.Service;

@Service
public class RemediationService {

    /**
     * Generates a formatted HTML string with remediation steps for a given security finding.
     * @param finding The security finding to generate steps for.
     * @return An HTML string containing the remediation guide.
     */
    public String getRemediationForFinding(DashboardData.SecurityFinding finding) {
        String genericSteps = String.format(
            "<p><strong>1. Identify the Resource:</strong> Locate the resource <strong>%s</strong> in the AWS console in the <strong>%s</strong> region.</p>" +
            "<p><strong>2. Analyze the Finding:</strong> Understand why this is flagged. The description is: <em>\"%s\"</em>.</p>" +
            "<p><strong>3. Follow Best Practices:</strong> Consult the official AWS documentation for the service (<strong>%s</strong>) to apply the recommended fix.</p>" +
            "<p><strong>4. Verify the Fix:</strong> After applying changes, re-run the security scan or wait for the next scheduled scan to confirm the issue is resolved.</p>",
            finding.getResourceId(),  // Changed from getResource()
            finding.getRegion(), 
            finding.getDescription(), 
            finding.getCategory()     // Changed from getType()
        );

        // Use category as a key for specific steps
        String findingKey = finding.getCategory();  // Changed from getType()
        String specificSteps = getSpecificSteps(findingKey);

        if (!specificSteps.isEmpty()) {
            return String.format("<h4>Specific Guidance:</h4>%s<hr class=\"my-4\"><h4>General Steps:</h4>%s", specificSteps, genericSteps);
        } else {
            return String.format("<h4>General Steps:</h4>%s", genericSteps);
        }
    }

    private String getSpecificSteps(String key) {
        switch (key) {
            case "IAM":
                return "<p><strong>Specific Action:</strong> Navigate to the IAM user and enforce Multi-Factor Authentication (MFA). This is critical for preventing unauthorized access.</p>";
            case "S3":
                return "<p><strong>Specific Action:</strong> Go to the S3 bucket settings. Enable \"Block all public access\" and review bucket policies and ACLs to remove public grants.</p>";
            case "SecurityGroup":
            case "VPC":  // VPC category includes security groups
                return "<p><strong>Specific Action:</strong> Edit the inbound rules for the security group. Restrict the source IP range from '0.0.0.0/0' to a specific, known IP address or range. Avoid opening ports like SSH (22) or RDP (3389) to the world. For VPCs, enable Flow Logs in the VPC console and configure them to send logs to CloudWatch or S3 for traffic analysis.</p>";
            case "CloudTrail":
                return "<p><strong>Specific Action:</strong> Navigate to the CloudTrail console and create a new trail. Ensure it is multi-region and has log file validation enabled. Configure it to log to an S3 bucket with appropriate encryption.</p>";
            default:
                return "";
        }
    }
}
