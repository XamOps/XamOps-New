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

        // All three providers are instrumented. The registry keys on the opaque provider
        // account id, so it needs no provider argument — getProviderAccountId already resolves
        // to the AWS account id, GCP project id or Azure subscription id as appropriate.
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
            return unreachableSummary(account, accountLabel, roleName);
        }

        boolean onlyNotEnabled = gaps.stream()
                .allMatch(gap -> gap.getKind() == PermissionGap.Kind.SERVICE_NOT_ENABLED);
        if (onlyNotEnabled) {
            String services = gaps.stream()
                    .map(PermissionGap::getServiceLabel)
                    .distinct()
                    .collect(Collectors.joining(", "));
            return services + " is not enabled in " + accountLabel
                    + ", so this data is not available. Enable it in the " + consoleNameOf(account)
                    + " and the data will appear on the next refresh.";
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

        return scope + " is hidden because " + identityPhraseOf(account, roleName) + " in " + accountLabel
                + " is missing " + actionList + ". " + remediationOf(account);
    }

    /**
     * What is broken when nothing at all can be read.
     *
     * <p>Worth three separate sentences rather than one generic one, because the fix is in a
     * different place for each provider and a customer following AWS instructions on a GCP
     * project gets nowhere.
     */
    private String unreachableSummary(CloudAccount account, String accountLabel, String roleName) {
        if (isGcp(account)) {
            return "XamOps can no longer authenticate to " + accountLabel
                    + ". The service account key has been deleted, disabled, or rotated. No data can be"
                    + " loaded for this project until a new key is supplied.";
        }
        if (isAzure(account)) {
            return "XamOps can no longer authenticate to " + accountLabel
                    + ". The service principal's client secret has expired or its app registration was"
                    + " removed. No data can be loaded for this subscription until it is restored.";
        }
        return "XamOps can no longer assume the " + roleName + " role in " + accountLabel
                + ". No data can be loaded for this account until the role and its trust policy are restored.";
    }

    /** How to refer to the thing holding the permissions. */
    private String identityPhraseOf(CloudAccount account, String roleName) {
        if (isGcp(account)) {
            return "the XamOps service account";
        }
        if (isAzure(account)) {
            return "the XamOps service principal";
        }
        return "the " + roleName + " role";
    }

    /** The action that actually fixes it, per provider. */
    private String remediationOf(CloudAccount account) {
        if (isGcp(account)) {
            return "Re-run the XamOps setup script for this project to grant the missing roles.";
        }
        if (isAzure(account)) {
            return "Add the XamOps role assignment back on this subscription to restore it.";
        }
        return "Re-apply the XamOps policy to this role to restore it.";
    }

    private String consoleNameOf(CloudAccount account) {
        if (isGcp(account)) {
            return "Google Cloud console";
        }
        if (isAzure(account)) {
            return "Azure portal";
        }
        return "AWS console";
    }

    // equalsIgnoreCase throughout: the provider column holds "Azure" rather than "AZURE", and
    // CloudAccount.getProviderAccountId switches on it case-sensitively, so assuming a casing
    // here is how a provider silently falls through to the AWS wording.
    private boolean isGcp(CloudAccount account) {
        return "GCP".equalsIgnoreCase(account.getProvider());
    }

    private boolean isAzure(CloudAccount account) {
        return "Azure".equalsIgnoreCase(account.getProvider());
    }

    /**
     * The onboarding stack names the role by access type; fall back to the ARN's role name.
     *
     * <p>AWS-only by nature. GCP and Azure never reach the call sites that use it — see
     * {@link #identityPhraseOf}.
     */
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
