package com.xammer.cloud.security;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserTabAccessRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A small two-tenant world for the CloudK8s guard tests: the real
 * {@link CloudAccountAccessService} and {@link K8sAutomationAccessService} over mocked
 * repositories that answer from in-memory rows, so entitlement is decided by the production
 * rules (client match for primary users), not by a stubbed {@code canAccess}.
 *
 * <p>
 * Tenant A is client 1 with CloudAccount id 7 (AWS {@value #TENANT_A_AWS}); tenant B is client 2
 * with CloudAccount id {@value #TENANT_B_DB_ID} (AWS {@value #TENANT_B_AWS}). Both register a
 * cluster with the same name, {@value #CLUSTER}, so a lookup that ignores the tenant would find
 * the other one's config.
 */
public final class K8sAutomationTestWorld {

    public static final String CLUSTER = "prod-eks";
    public static final String TENANT_A_AWS = "111111111111";
    public static final String TENANT_B_AWS = "222222222222";
    public static final long TENANT_A_DB_ID = 7L;
    public static final long TENANT_B_DB_ID = 42L;
    public static final long TENANT_A_CONFIG_ID = 100L;
    public static final long TENANT_B_CONFIG_ID = 200L;

    public final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    public final KubernetesClusterConfigRepository clusterConfigRepository = mock(KubernetesClusterConfigRepository.class);
    public final UserRepository userRepository = mock(UserRepository.class);
    public final UserTabAccessRepository userTabAccessRepository = mock(UserTabAccessRepository.class);
    public final UserCloudAccountAccessRepository userCloudAccountAccessRepository =
            mock(UserCloudAccountAccessRepository.class);
    public final CloudAccountAccessService cloudAccountAccessService =
            new CloudAccountAccessService(userRepository, userCloudAccountAccessRepository, cloudAccountRepository);
    public final K8sAutomationAccessService accessService = new K8sAutomationAccessService(
            cloudAccountAccessService, cloudAccountRepository, clusterConfigRepository, userRepository,
            userTabAccessRepository);

    private final Map<Long, CloudAccount> accounts = new HashMap<>();
    private final List<KubernetesClusterConfig> configs = new ArrayList<>();
    private final Map<Long, User> users = new HashMap<>();
    private final Map<Long, List<String>> tabs = new HashMap<>();
    private final Map<Long, Client> clients = new HashMap<>();

    public final CloudAccount tenantA;
    public final CloudAccount tenantB;
    public final KubernetesClusterConfig tenantAConfig;
    public final KubernetesClusterConfig tenantBConfig;

    public K8sAutomationTestWorld() {
        when(cloudAccountRepository.findByAwsAccountId(anyString())).thenAnswer(inv -> accounts.values().stream()
                .filter(a -> Objects.equals(a.getAwsAccountId(), inv.getArgument(0))).toList());
        when(cloudAccountRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(accounts.get(inv.<Long>getArgument(0))));
        when(clusterConfigRepository.findByCloudAccountIdAndClusterName(anyLong(), anyString()))
                .thenAnswer(inv -> configs.stream()
                        .filter(c -> c.getCloudAccountId().equals(inv.getArgument(0))
                                && c.getClusterName().equals(inv.getArgument(1)))
                        .findFirst());
        when(clusterConfigRepository.findByCloudAccountId(anyLong())).thenAnswer(inv -> configs.stream()
                .filter(c -> c.getCloudAccountId().equals(inv.getArgument(0))).toList());
        when(clusterConfigRepository.findById(anyLong())).thenAnswer(inv -> configs.stream()
                .filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());
        when(userRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<Long>getArgument(0))));
        when(userTabAccessRepository.findTabNamesByUserId(anyLong()))
                .thenAnswer(inv -> tabs.getOrDefault(inv.<Long>getArgument(0), List.of()));
        when(userCloudAccountAccessRepository.findCloudAccountIdsByUserId(anyLong())).thenReturn(List.of());

        tenantA = addAccount(TENANT_A_DB_ID, TENANT_A_AWS, 1L, "read-write", "CONNECTED");
        tenantB = addAccount(TENANT_B_DB_ID, TENANT_B_AWS, 2L, "read-write", "CONNECTED");
        tenantAConfig = addConfig(TENANT_A_CONFIG_ID, TENANT_A_DB_ID, CLUSTER);
        tenantBConfig = addConfig(TENANT_B_CONFIG_ID, TENANT_B_DB_ID, CLUSTER);
    }

    public CloudAccount addAccount(long id, String awsId, long clientId, String accessType, String status) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setAwsAccountId(awsId);
        a.setProvider("AWS");
        a.setAccountName("account-" + id);
        a.setAccessType(accessType);
        a.setStatus(status);
        a.setClient(clients.computeIfAbsent(clientId, cid -> {
            Client c = new Client();
            c.setId(cid);
            c.setName("client-" + cid);
            return c;
        }));
        accounts.put(id, a);
        return a;
    }

    public KubernetesClusterConfig addConfig(long id, long cloudAccountId, String clusterName) {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(id);
        c.setCloudAccountId(cloudAccountId);
        c.setClusterName(clusterName);
        c.setRegion("us-east-1");
        c.setAutoscalingEnabled(true);
        configs.add(c);
        return c;
    }

    /**
     * Adds a primary user of {@code clientId} (no {@code createdBy}, so client-wide account
     * access) and returns its principal. {@code tabNames} empty means unrestricted.
     *
     * @param principalRole the authority the session carries (the DB role decides the gate)
     */
    public ClientUserDetails addUser(long id, long clientId, String email, String dbRole, String principalRole,
                                     List<String> tabNames) {
        User u = new User();
        u.setId(id);
        u.setUsername("user-" + id);
        u.setEmail(email);
        u.setRole(dbRole);
        u.setClient(clients.get(clientId));
        users.put(id, u);
        tabs.put(id, List.copyOf(tabNames));
        return new ClientUserDetails(u.getUsername(), "pw", List.of(new SimpleGrantedAuthority(principalRole)),
                clientId, id, "default");
    }

    /** Puts the principal in the security context, as Spring Security does for a session. */
    public static void signIn(ClientUserDetails principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
