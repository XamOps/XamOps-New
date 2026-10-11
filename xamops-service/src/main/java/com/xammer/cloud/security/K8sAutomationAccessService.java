package com.xammer.cloud.security;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.exception.NoUserPermissionDetail;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Who may read and change CloudK8s automation, decided once on the server (design §6.2, §6.6, D2).
 *
 * <ul>
 *   <li>{@link #resolveAccounts}: the caller's AWS account number is matched only against
 *       {@code CloudAccount.awsAccountId} (padded to 12 digits and as given), and only rows
 *       {@link CloudAccountAccessService#canAccess} allows are kept. It is never used as a
 *       {@code cloud_account_id}: naming another tenant's {@code CloudAccount.id} matches no AWS
 *       account and is refused like any account that isn't yours.</li>
 *   <li>{@link #resolve}: finds the cluster config by each entitled row's DB id, then by that
 *       row's own AWS number (legacy rows, B4).</li>
 *   <li>{@link #writeDecision}: the ordered write gates: {@code DEMO} (by the user row's email,
 *       never the username), {@code NO_USER_PERMISSION} for an impersonated session, a role that
 *       can't change automation, or a missing grant, then {@code READ_ONLY_CONNECTION} and
 *       {@code CONNECTION_NOT_VERIFIED}.</li>
 * </ul>
 *
 * <p>
 * Public and self-contained so the vertical engine can call it instead of re-implementing the
 * rules (design §6.6, R2). The capabilities endpoint uses the same methods, so the UI and the
 * interceptor can't disagree.
 */
@Service
public class K8sAutomationAccessService {

    private static final Logger log = LoggerFactory.getLogger(K8sAutomationAccessService.class);

    /**
     * Shared demo identities, compared with the user row's trimmed, lower-cased email. Mirrors
     * the private set in {@code LoginOtpController} (which stays untouched);
     * {@code K8sAutomationAccessServiceTest} fails if the two drift.
     */
    public static final Set<String> DEMO_EMAILS = Set.of("demo.xamops@xammer.in", "demo.billops@xammer.in");

    /** Sidebar key of the CloudK8s tab. */
    public static final String CLOUDK8S_TAB = "cloudops-cloudk8s";

    /** Permission key that lets a restricted user change automation (D2). Not a sidebar tab. */
    public static final String AUTOMATION_PERMISSION = "cloudops-cloudk8s-automation";

    /** XamOps-product roles that may change automation, after {@code ROLE_} normalisation. */
    static final Set<String> AUTOMATION_ROLES = Set.of(
            "ROLE_XAMOPS", "ROLE_XAMOPS_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER");

    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("^[0-9]{1,12}$");

    private final CloudAccountAccessService cloudAccountAccessService;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final UserRepository userRepository;
    private final UserTabAccessRepository userTabAccessRepository;

    public K8sAutomationAccessService(CloudAccountAccessService cloudAccountAccessService,
                                      CloudAccountRepository cloudAccountRepository,
                                      KubernetesClusterConfigRepository clusterConfigRepository,
                                      UserRepository userRepository,
                                      UserTabAccessRepository userTabAccessRepository) {
        this.cloudAccountAccessService = cloudAccountAccessService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.userRepository = userRepository;
        this.userTabAccessRepository = userTabAccessRepository;
    }

    // ── Decisions ─────────────────────────────────────────────────────────

    /**
     * The user half of the write gates. At most one reason: {@code DEMO}, or
     * {@code NO_USER_PERMISSION} with its detail.
     */
    public record UserDecision(List<K8sAutomationReason> reasons, NoUserPermissionDetail detail) {
        public UserDecision {
            reasons = List.copyOf(reasons);
        }

        public boolean canChangeAutomation() {
            return reasons.isEmpty();
        }
    }

    /**
     * Every write gate that fails, in refusal order (user reason first, then
     * {@code READ_ONLY_CONNECTION}, then {@code CONNECTION_NOT_VERIFIED}).
     */
    public record WriteDecision(List<K8sAutomationReason> reasons, NoUserPermissionDetail detail) {
        public WriteDecision {
            reasons = List.copyOf(reasons);
        }

        public boolean allowed() {
            return reasons.isEmpty();
        }

        /** The refusal the server answers with, or empty when writes are allowed. */
        public Optional<K8sAutomationRefusedException> firstRefusal() {
            if (reasons.isEmpty()) {
                return Optional.empty();
            }
            K8sAutomationReason first = reasons.get(0);
            if (first == K8sAutomationReason.NO_USER_PERMISSION) {
                return Optional.of(K8sAutomationRefusedException.noUserPermission(
                        detail != null ? detail : NoUserPermissionDetail.TAB_GRANT));
            }
            return Optional.of(K8sAutomationRefusedException.of(first));
        }
    }

    // ── Account and cluster resolution ────────────────────────────────────

    /**
     * The AWS {@code CloudAccount} rows the user may use for this account number: the padded and
     * the raw form (the column is unique, so at most one of each), AWS rows only, filtered by
     * {@link CloudAccountAccessService#canAccess}.
     *
     * @throws K8sAutomationRefusedException {@code 403 K8S_NO_ACCOUNT_ACCESS}, with the same
     *                                       message for an unknown account, an invalid number
     *                                       and an account that isn't the caller's
     */
    public List<CloudAccount> resolveAccounts(ClientUserDetails user, String awsAccountId) {
        if (user == null || awsAccountId == null || !AWS_ACCOUNT_ID.matcher(awsAccountId.trim()).matches()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.NO_ACCOUNT_ACCESS);
        }
        String raw = awsAccountId.trim();
        String padded = "0".repeat(12 - raw.length()) + raw;

        Map<Long, CloudAccount> entitled = new LinkedHashMap<>();
        Stream.of(padded, raw).distinct()
                .flatMap(candidate -> cloudAccountRepository.findByAwsAccountId(candidate).stream())
                .filter(a -> a != null && a.getId() != null && "AWS".equalsIgnoreCase(a.getProvider()))
                .filter(a -> cloudAccountAccessService.canAccess(user, a))
                .forEach(a -> entitled.putIfAbsent(a.getId(), a));

        if (entitled.isEmpty()) {
            throw K8sAutomationRefusedException.of(K8sAutomationReason.NO_ACCOUNT_ACCESS);
        }
        return List.copyOf(entitled.values());
    }

    /**
     * Resolves the entitled rows and the cluster config. The config is looked up by each
     * entitled row's DB id first, then by that row's own AWS number (legacy rows keyed by the
     * account number, B4); the caller's input is never used as a {@code cloud_account_id}.
     * The owning row is listed first. A {@code null} config means the cluster isn't registered.
     */
    public K8sAutomationRequestContext resolve(ClientUserDetails user, String awsAccountId, String clusterName) {
        List<CloudAccount> accounts = resolveAccounts(user, awsAccountId);

        for (CloudAccount account : accounts) {
            Optional<KubernetesClusterConfig> modern =
                    clusterConfigRepository.findByCloudAccountIdAndClusterName(account.getId(), clusterName);
            if (modern.isPresent()) {
                List<CloudAccount> ordered = new ArrayList<>();
                ordered.add(account);
                accounts.stream().filter(a -> !a.getId().equals(account.getId())).forEach(ordered::add);
                return new K8sAutomationRequestContext(user, ordered, modern.get());
            }
        }

        Set<Long> entitledIds = accounts.stream().map(CloudAccount::getId).collect(Collectors.toSet());
        for (CloudAccount account : accounts) {
            Long awsNumber = legacyKey(account, entitledIds);
            if (awsNumber == null) {
                continue;
            }
            Optional<KubernetesClusterConfig> legacy =
                    clusterConfigRepository.findByCloudAccountIdAndClusterName(awsNumber, clusterName);
            if (legacy.isPresent()) {
                return new K8sAutomationRequestContext(user, accounts, legacy.get());
            }
        }
        return new K8sAutomationRequestContext(user, accounts, null);
    }

    /**
     * Every cluster config registered for the entitled rows: modern rows (keyed by DB id) first,
     * then legacy rows (keyed by the AWS number), de-duplicated by cluster name.
     */
    public List<KubernetesClusterConfig> configsFor(List<CloudAccount> accounts) {
        Map<String, KubernetesClusterConfig> byName = new LinkedHashMap<>();
        for (CloudAccount account : accounts) {
            for (KubernetesClusterConfig c : clusterConfigRepository.findByCloudAccountId(account.getId())) {
                if (c.getClusterName() != null) {
                    byName.putIfAbsent(c.getClusterName(), c);
                }
            }
        }
        Set<Long> entitledIds = accounts.stream().map(CloudAccount::getId).collect(Collectors.toSet());
        for (CloudAccount account : accounts) {
            Long awsNumber = legacyKey(account, entitledIds);
            if (awsNumber == null) {
                continue;
            }
            for (KubernetesClusterConfig c : clusterConfigRepository.findByCloudAccountId(awsNumber)) {
                if (c.getClusterName() != null) {
                    byName.putIfAbsent(c.getClusterName(), c);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * The account that owns a config, for paths that start from the config (the agent's policy
     * fetch): the row with the config's DB id, else the AWS row with the padded account number
     * (legacy configs). Empty when neither exists.
     */
    public Optional<CloudAccount> findOwningAccount(KubernetesClusterConfig config) {
        if (config == null || config.getCloudAccountId() == null) {
            return Optional.empty();
        }
        Optional<CloudAccount> byId = cloudAccountRepository.findById(config.getCloudAccountId());
        if (byId.isPresent()) {
            return byId;
        }
        String padded = String.format("%012d", config.getCloudAccountId());
        return cloudAccountRepository.findByAwsAccountId(padded).stream()
                .filter(a -> a != null && "AWS".equalsIgnoreCase(a.getProvider()))
                .findFirst();
    }

    /**
     * The AWS number a legacy config for this row would be keyed by, or {@code null} when the
     * row has no numeric AWS id, or when that number is also the DB id of a CloudAccount the
     * caller isn't entitled to (a modern config of another tenant must never be read as a
     * legacy config of this one).
     */
    private Long legacyKey(CloudAccount account, Set<Long> entitledIds) {
        String awsId = account.getAwsAccountId();
        if (awsId == null || !AWS_ACCOUNT_ID.matcher(awsId.trim()).matches()) {
            return null;
        }
        long awsNumber = Long.parseLong(awsId.trim());
        if (!entitledIds.contains(awsNumber) && cloudAccountRepository.findById(awsNumber).isPresent()) {
            log.warn("Skipping legacy cluster lookup for account {}: its AWS number is another account's id",
                    account.getId());
            return null;
        }
        return awsNumber;
    }

    // ── Write gates ───────────────────────────────────────────────────────

    /**
     * The user part of the write gates (design §6.6 step 7, D2), in order: {@code DEMO} by the
     * user row's email, then {@code NO_USER_PERMISSION} for an impersonated session, a role that
     * isn't a XamOps-product role, or a restricted user without both tab keys. A principal
     * without an id, or whose row can't be loaded, fails closed.
     */
    public UserDecision userDecision(ClientUserDetails user) {
        if (user == null || user.getId() == null) {
            log.warn("K8s automation: principal {} has no user id; refusing writes",
                    user != null ? user.getUsername() : null);
            return new UserDecision(List.of(K8sAutomationReason.NO_USER_PERMISSION), NoUserPermissionDetail.TAB_GRANT);
        }
        User row = userRepository.findById(user.getId()).orElse(null);
        if (row == null) {
            log.warn("K8s automation: no user row for principal {} (id {}); refusing writes",
                    user.getUsername(), user.getId());
            return new UserDecision(List.of(K8sAutomationReason.NO_USER_PERMISSION), NoUserPermissionDetail.TAB_GRANT);
        }
        if (isDemoEmail(row.getEmail())) {
            return new UserDecision(List.of(K8sAutomationReason.DEMO), null);
        }
        if (ImpersonationContext.getImpersonatedUserId() != null) {
            return new UserDecision(List.of(K8sAutomationReason.NO_USER_PERMISSION),
                    NoUserPermissionDetail.IMPERSONATION);
        }
        if (!AUTOMATION_ROLES.contains(normalizeRole(row.getRole()))) {
            return new UserDecision(List.of(K8sAutomationReason.NO_USER_PERMISSION), NoUserPermissionDetail.ROLE);
        }
        List<String> tabs = userTabAccessRepository.findTabNamesByUserId(row.getId());
        boolean restricted = tabs != null && !tabs.isEmpty();
        if (restricted && !(tabs.contains(CLOUDK8S_TAB) && tabs.contains(AUTOMATION_PERMISSION))) {
            return new UserDecision(List.of(K8sAutomationReason.NO_USER_PERMISSION), NoUserPermissionDetail.TAB_GRANT);
        }
        return new UserDecision(List.of(), null);
    }

    /**
     * Every failing write gate for this user over these account rows. The account gates use the
     * stricter of the rows: read-only if any row is, not verified if any row isn't
     * {@code CONNECTED}. Pass the owning row alone when there is one
     * ({@link K8sAutomationRequestContext#gateAccounts()}).
     */
    public WriteDecision writeDecision(ClientUserDetails user, List<CloudAccount> accounts) {
        UserDecision userPart = userDecision(user);
        List<K8sAutomationReason> reasons = new ArrayList<>(userPart.reasons());
        reasons.addAll(accountReasons(accounts));
        return new WriteDecision(reasons, userPart.detail());
    }

    /** {@code READ_ONLY_CONNECTION} and/or {@code CONNECTION_NOT_VERIFIED}, stricter row wins. */
    public static List<K8sAutomationReason> accountReasons(List<CloudAccount> accounts) {
        List<K8sAutomationReason> reasons = new ArrayList<>();
        if (accounts == null || accounts.isEmpty()) {
            reasons.add(K8sAutomationReason.READ_ONLY_CONNECTION);
            reasons.add(K8sAutomationReason.CONNECTION_NOT_VERIFIED);
            return reasons;
        }
        if (accounts.stream().anyMatch(a -> !isReadWrite(a))) {
            reasons.add(K8sAutomationReason.READ_ONLY_CONNECTION);
        }
        if (accounts.stream().anyMatch(a -> !isConnected(a))) {
            reasons.add(K8sAutomationReason.CONNECTION_NOT_VERIFIED);
        }
        return reasons;
    }

    /**
     * Why the automation agent of this config is served a disabled policy (NIT 11), or {@code null}
     * when its owning account is read-write and {@code CONNECTED}. An owner that can't be found
     * blocks with {@code CONNECTION_NOT_VERIFIED}. The agent serve path and the dashboard both use
     * this, so they can't disagree.
     */
    public K8sAutomationReason agentPolicyBlock(KubernetesClusterConfig config) {
        Optional<CloudAccount> owner = findOwningAccount(config);
        if (owner.isEmpty()) {
            return K8sAutomationReason.CONNECTION_NOT_VERIFIED;
        }
        return accountBlock(owner.get());
    }

    /** The first account gate that blocks this row, or {@code null} when it allows automation. */
    public static K8sAutomationReason accountBlock(CloudAccount account) {
        List<K8sAutomationReason> reasons = accountReasons(account == null ? List.of() : List.of(account));
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    /** {@code accessType} equals {@code read-write} ignoring case; null and anything else are read-only (D1). */
    public static boolean isReadWrite(CloudAccount account) {
        return account != null && account.getAccessType() != null
                && "read-write".equalsIgnoreCase(account.getAccessType().trim());
    }

    /** Status only: {@code roleArn} and {@code externalId} don't matter for Kubernetes automation. */
    public static boolean isConnected(CloudAccount account) {
        return account != null && "CONNECTED".equalsIgnoreCase(account.getStatus());
    }

    public static boolean isDemoEmail(String email) {
        return email != null && DEMO_EMAILS.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code "XAMOPS"} → {@code "ROLE_XAMOPS"}, as {@code ImpersonationFilter} normalises it. */
    static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
    }
}
