package com.xammer.lobtext.billops;

import com.xammer.billops.domain.Client;
import com.xammer.billops.dto.DemoUserLoginAgg;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.UserActivityEventRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.domain.type.PgTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-slice coverage for the admin "Demo Users" data path (FEAT-004) against a throwaway
 * local PostgreSQL. Opt-in: gated on {@code LOBTEXT_TEST_JDBC_URL} like the sibling PG tests, so CI's
 * fast suite reports it as skipped.
 *
 * <p>Asserts that:
 * <ul>
 *   <li>{@code AppUserRepository.findByClient_Demo(true)} returns only users of the {@code is_demo}
 *       client and excludes users of a non-demo client;</li>
 *   <li>{@code aggregateLoginsByUserIds} returns correct min/max/count and the per-user
 *       {@code authProvider}, ignoring non-LOGIN actions;</li>
 *   <li>{@code findFirstByUserIdAndActionOrderByCreatedAtDesc(id, "LOGIN")} returns the latest LOGIN
 *       row's {@code ip_address};</li>
 *   <li>a demo-client user with zero LOGIN rows has no aggregate row (DTO renders null/0).</li>
 * </ul>
 */
@DataJpaTest
@ContextConfiguration(classes = LobTextBillopsPgConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("lobtext-pg")
@EnabledIfEnvironmentVariable(named = "LOBTEXT_TEST_JDBC_URL", matches = ".+")
class DemoUsersRepositoryPgTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PgTestSupport.requireLocal();
        String url = PgTestSupport.recreateDatabase("demo_users_repo");
        // Let Hibernate create the schema for the mapped entities on the throwaway DB.
        PgTestSupport.springProperties(url, "create").forEach((k, v) -> registry.add(k, () -> v));
    }

    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private UserActivityEventRepository eventRepository;

    @Test
    void demoUserSetLoginAggregateAndLastIpAreDerivedFromLoginRows() {
        Client demoClient = new Client("demo-client");
        demoClient.setDemo(true);
        demoClient = clientRepository.save(demoClient);

        Client normalClient = new Client("real-client");
        normalClient.setDemo(false);
        normalClient = clientRepository.save(normalClient);

        AppUser demoWithLogins = appUserRepository.save(user("alice", "alice@example.com", demoClient));
        AppUser demoNoLogins = appUserRepository.save(user("bob", "bob@example.com", demoClient));
        AppUser nonDemo = appUserRepository.save(user("carol", "carol@example.com", normalClient));

        // alice: three LOGIN rows (GOOGLE) spread across three timestamps + one non-LOGIN row.
        saveLogin(demoWithLogins.getId(), "GOOGLE", "198.51.100.1", LocalDateTime.of(2026, 1, 1, 9, 0));
        saveLogin(demoWithLogins.getId(), "GOOGLE", "198.51.100.2", LocalDateTime.of(2026, 1, 2, 9, 0));
        saveLogin(demoWithLogins.getId(), "GOOGLE", "198.51.100.3", LocalDateTime.of(2026, 1, 3, 9, 0));
        saveEvent(demoWithLogins.getId(), UserActivityEvent.ACTION_IMPERSONATION_STARTED, null, "10.0.0.9",
                LocalDateTime.of(2026, 1, 4, 9, 0));
        // nonDemo user also has a LOGIN row — must never surface via the demo query.
        saveLogin(nonDemo.getId(), "MICROSOFT", "203.0.113.5", LocalDateTime.of(2026, 1, 1, 10, 0));

        // findByClient_Demo(true) returns only demo-client users.
        List<AppUser> demoUsers = appUserRepository.findByClient_Demo(true);
        assertThat(demoUsers).extracting(AppUser::getEmail)
                .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");

        // aggregateLoginsByUserIds: min/max/count/provider, ignoring the non-LOGIN row and bob (no LOGIN).
        List<Long> ids = demoUsers.stream().map(AppUser::getId).collect(Collectors.toList());
        Map<Long, DemoUserLoginAgg> agg = eventRepository.aggregateLoginsByUserIds(ids).stream()
                .collect(Collectors.toMap(DemoUserLoginAgg::getUserId, Function.identity()));

        assertThat(agg).containsKey(demoWithLogins.getId());
        assertThat(agg).doesNotContainKey(demoNoLogins.getId());
        DemoUserLoginAgg aliceAgg = agg.get(demoWithLogins.getId());
        assertThat(aliceAgg.getLoginCount()).isEqualTo(3L);
        assertThat(aliceAgg.getAuthProvider()).isEqualTo("GOOGLE");
        assertThat(aliceAgg.getFirstSeen()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(aliceAgg.getLastLogin()).isEqualTo(LocalDateTime.of(2026, 1, 3, 9, 0));

        // last IP = ip_address of the most-recent LOGIN row.
        Optional<UserActivityEvent> lastLogin = eventRepository
                .findFirstByUserIdAndActionOrderByCreatedAtDesc(demoWithLogins.getId(), "LOGIN");
        assertThat(lastLogin).isPresent();
        assertThat(lastLogin.get().getIpAddress()).isEqualTo("198.51.100.3");
    }

    private static AppUser user(String username, String email, Client client) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setEmail(email);
        u.setPassword("x");
        u.setRole("ROLE_XAMOPS");
        u.setClient(client);
        return u;
    }

    private void saveLogin(Long userId, String provider, String ip, LocalDateTime at) {
        saveEvent(userId, UserActivityEvent.ACTION_LOGIN, provider, ip, at);
    }

    private void saveEvent(Long userId, String action, String provider, String ip, LocalDateTime at) {
        UserActivityEvent e = new UserActivityEvent();
        e.setUserId(userId);
        e.setAction(action);
        e.setAuthProvider(provider);
        e.setIpAddress(ip);
        e.setCreatedAt(at);
        eventRepository.save(e);
    }
}
