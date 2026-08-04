package com.xammer.cloud.permissions;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns raw denials into the sentence a customer reads at the bottom of a page.
 *
 * The wording matters more than it looks: the customer's next action is editing
 * an IAM policy, so the message has to name the exact action and the exact role,
 * not just say "some data is unavailable".
 */
@Service
public class PermissionGapService {

    private final PermissionGapRegistry registry;

    public PermissionGapService(PermissionGapRegistry registry) {
        this.registry = registry;
    }

    /**
     * Builds the status for one account, optionally narrowed to a single feature
     * so a page only reports the gaps that actually affect what is on screen.
     */
    public AccountPermissionStatusDto statusFor(CloudAccount account, XamOpsFeature feature) {
        AccountPermissionStatusDto dto = new AccountPermissionStatusDto();
        dto.setAccountId(account.getProviderAccountId());
        dto.setAccountName(account.getAccountName());
        dto.setProvider(account.getProvider());
        dto.setAccessType(account.getAccessType());

        List<PermissionGap> gaps = registry.findByAccount(account.getProviderAccountId());
        if (feature != null) {
            gaps = gaps.stream()
                    .filter(gap -> gap.getFeatures().contains(feature.name()))
                    .collect(Collectors.toList());
        }

        if (gaps.isEmpty()) {
            dto.setHealth(AccountPermissionStatusDto.Health.HEALTHY);
            return dto;
        }

        boolean roleUnreachable = gaps.stream()
                .anyMatch(gap -> gap.getKind() == PermissionGap.Kind.ROLE_UNREACHABLE);

        dto.setHealth(roleUnreachable
                ? AccountPermissionStatusDto.Health.UNREACHABLE
                : AccountPermissionStatusDto.Health.DEGRADED);
        dto.setGaps(gaps);
        dto.setMissingActions(gaps.stream().map(PermissionGap::getAction).distinct().collect(Collectors.toList()));

        Set<String> featureLabels = new LinkedHashSet<>();
        gaps.forEach(gap -> featureLabels.addAll(gap.getFeatureLabels()));
        dto.setAffectedFeatures(new ArrayList<>(featureLabels));

        dto.setSummary(buildSummary(account, gaps, roleUnreachable, feature));
        return dto;
    }

    private String buildSummary(CloudAccount account, List<PermissionGap> gaps, boolean roleUnreachable,
            XamOpsFeature feature) {

        String roleName = roleNameOf(account);
        String accountLabel = account.getAccountName() != null
                ? account.getAccountName()
                : account.getProviderAccountId();

        if (roleUnreachable) {
            return "XamOps can no longer assume the " + roleName + " role in " + accountLabel
                    + ". No data can be loaded for this account until the role and its trust policy are restored.";
        }

        boolean onlyNotEnabled = gaps.stream()
                .allMatch(gap -> gap.getKind() == PermissionGap.Kind.SERVICE_NOT_ENABLED);
        if (onlyNotEnabled) {
            String services = gaps.stream()
                    .map(PermissionGap::getServiceLabel)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return services + " is not enabled in " + accountLabel
                    + ", so this data is not available. Enable it in the AWS console and the data will appear on the next refresh.";
        }

        List<String> actions = gaps.stream()
                .filter(gap -> gap.getKind() == PermissionGap.Kind.PERMISSION)
                .map(PermissionGap::getAction)
                .distinct()
                .collect(Collectors.toList());

        String scope = feature != null ? "Some " + feature.getLabel() + " data" : "Some data";
        String actionList = actions.size() <= 3
                ? String.join(", ", actions)
                : String.join(", ", actions.subList(0, 3)) + " and " + (actions.size() - 3) + " more";

        return scope + " is hidden because the " + roleName + " role in " + accountLabel
                + " is missing " + actionList + ". Re-apply the XamOps policy to this role to restore it.";
    }

    /** The onboarding stack names the role by access type; fall back to the ARN's role name. */
    private String roleNameOf(CloudAccount account) {
        String roleArn = account.getRoleArn();
        if (roleArn != null && roleArn.contains("/")) {
            return roleArn.substring(roleArn.lastIndexOf('/') + 1);
        }
        return "read-write".equalsIgnoreCase(account.getAccessType())
                ? "XamOpsReadWriteRole"
                : "XamOpsReadOnlyRole";
    }
}
