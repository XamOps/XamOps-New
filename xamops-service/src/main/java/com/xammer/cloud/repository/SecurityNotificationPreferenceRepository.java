package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SecurityNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Preferences, keyed by client id which is also the primary key.
 *
 * <p>
 * {@code findById} is the only lookup needed, and callers must treat an empty result as
 * "defaults apply" rather than "notifications off" — see
 * {@link SecurityNotificationPreference#defaults(Long)}. Getting that backwards would silence
 * the feature for every existing client, which is the failure mode that looks like the feature
 * simply not working.
 */
@Repository
public interface SecurityNotificationPreferenceRepository
        extends JpaRepository<SecurityNotificationPreference, Long> {
}
