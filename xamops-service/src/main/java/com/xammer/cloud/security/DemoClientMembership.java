package com.xammer.cloud.security;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Whether a user belongs to the shared read-only demo client ({@code client.is_demo = true}).
 *
 * <p>This is the <strong>client-is-demo</strong> signal the four automation/admin write-gates read
 * alongside — never instead of — their existing {@code DEMO_EMAILS} email checks (R7). The two
 * hardcoded demo emails live on the real {@code defaultClient} (whose {@code is_demo} is false), so
 * the email checks still block them; this adds the orthogonal case of a host-provisioned user
 * attached to the demo client.
 *
 * <p>The user's {@code client} association is {@code LAZY}. The automation write-gates call this from
 * an MVC interceptor ({@code K8sAutomationGuardInterceptor#preHandle}) that runs <em>before</em> the
 * request transaction opens, so a plain {@code findById} leaves {@code client} a detached proxy and
 * {@code client.isDemo()} throws {@code LazyInitializationException}. This helper therefore loads the
 * row through {@link UserRepository#findByIdWithClient}, which {@code JOIN FETCH}es the client inside
 * the query so the result is safe to read with no open session. The read is cheap and the gates are
 * not a hot path.
 */
@Component
public class DemoClientMembership {

    private final UserRepository userRepository;

    public DemoClientMembership(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Whether the user with this id is attached to a {@code is_demo = true} client.
     *
     * @param userId the {@code app_user.id}; a null id or a missing row reads as {@code false}
     */
    public boolean isDemoClientUser(Long userId) {
        if (userId == null) {
            return false;
        }
        Optional<User> row = userRepository.findByIdWithClient(userId);
        return row.map(User::getClient)
                .map(client -> client.isDemo())
                .orElse(false);
    }
}
