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
 * <p>The user's {@code client} association is {@code LAZY}, so this helper never traverses it off a
 * session. It loads the row through {@link UserRepository#findById}, whose query is a plain select;
 * because the gates call this inside a request-scoped transaction the association resolves there.
 * The read is cheap and the gates are not a hot path.
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
        Optional<User> row = userRepository.findById(userId);
        return row.map(User::getClient)
                .map(client -> client.isDemo())
                .orElse(false);
    }
}
