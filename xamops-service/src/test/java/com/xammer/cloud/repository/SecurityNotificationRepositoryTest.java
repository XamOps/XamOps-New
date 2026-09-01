package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SecurityNotification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the findings inbox.
 *
 * <p>
 * The assertion that matters most is {@link #markReadCannotTouchAnotherClientsNotification()}.
 * {@code markRead} takes the client id and puts it in the {@code WHERE} clause, so a request
 * carrying someone else's notification id updates nothing. The tempting alternative — load by id,
 * check the client matches, then update — is the same vulnerability with an extra query, and it
 * passes every test that only ever uses one tenant.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class SecurityNotificationRepositoryTest {

    @Autowired
    SecurityNotificationRepository repository;

    private static final Long CLIENT = 7L;
    private static final Long OTHER_CLIENT = 8L;

    private SecurityNotification notification(Long clientId, String title, boolean read,
            Instant createdAt) {
        SecurityNotification n = new SecurityNotification();
        n.setClientId(clientId);
        n.setAwsAccountId("123456789012");
        n.setTitle(title);
        n.setMessage("body");
        n.setSeverity("CRITICAL");
        n.setFindingCount(1);
        n.setRead(read);
        n.setCreatedAt(createdAt);
        return repository.save(n);
    }

    @Test
    void feedIsNewestFirstAndScopedToTheClient() {
        Instant now = Instant.now();
        notification(CLIENT, "older", false, now.minus(2, ChronoUnit.HOURS));
        notification(CLIENT, "newest", false, now);
        notification(OTHER_CLIENT, "theirs", false, now);

        List<SecurityNotification> feed = repository.findForClient(CLIENT, PageRequest.of(0, 10));

        assertThat(feed).extracting(SecurityNotification::getTitle)
                .containsExactly("newest", "older");
    }

    @Test
    void feedRespectsTheRequestedLimit() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            notification(CLIENT, "n" + i, false, now.minus(i, ChronoUnit.MINUTES));
        }

        assertThat(repository.findForClient(CLIENT, PageRequest.of(0, 2))).hasSize(2);
    }

    @Test
    void unreadCountIgnoresReadRowsAndOtherClients() {
        Instant now = Instant.now();
        notification(CLIENT, "unread-1", false, now);
        notification(CLIENT, "unread-2", false, now);
        notification(CLIENT, "read", true, now);
        notification(OTHER_CLIENT, "theirs", false, now);

        assertThat(repository.countByClientIdAndReadFalse(CLIENT)).isEqualTo(2);
        assertThat(repository.countByClientIdAndReadFalse(OTHER_CLIENT)).isEqualTo(1);
    }

    @Test
    void markReadMarksTheOwnersNotification() {
        SecurityNotification mine = notification(CLIENT, "mine", false, Instant.now());

        assertThat(repository.markRead(mine.getId(), CLIENT)).isEqualTo(1);
        assertThat(repository.countByClientIdAndReadFalse(CLIENT)).isZero();
    }

    /**
     * The cross-tenant guard. Zero rows updated, and the other client's notification is untouched
     * and still unread.
     */
    @Test
    void markReadCannotTouchAnotherClientsNotification() {
        SecurityNotification theirs = notification(OTHER_CLIENT, "theirs", false, Instant.now());

        int updated = repository.markRead(theirs.getId(), CLIENT);

        assertThat(updated).isZero();
        assertThat(repository.countByClientIdAndReadFalse(OTHER_CLIENT)).isEqualTo(1);
    }

    @Test
    void markAllReadIsScopedToTheClient() {
        Instant now = Instant.now();
        notification(CLIENT, "a", false, now);
        notification(CLIENT, "b", false, now);
        notification(OTHER_CLIENT, "theirs", false, now);

        assertThat(repository.markAllRead(CLIENT)).isEqualTo(2);
        assertThat(repository.countByClientIdAndReadFalse(CLIENT)).isZero();
        assertThat(repository.countByClientIdAndReadFalse(OTHER_CLIENT)).isEqualTo(1);
    }

    /** The inbox is a feed, not an archive — the findings table holds the durable record. */
    @Test
    void purgeRemovesOnlyRowsOlderThanTheCutoff() {
        Instant now = Instant.now();
        notification(CLIENT, "keep", false, now);
        notification(CLIENT, "old", true, now.minus(45, ChronoUnit.DAYS));

        assertThat(repository.purgeBefore(now.minus(30, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(repository.findAll()).extracting(SecurityNotification::getTitle)
                .containsExactly("keep");
    }

    /** Read state defaults to unread, so a new notification always shows on the bell. */
    @Test
    void newNotificationsDefaultToUnread() {
        SecurityNotification saved = notification(CLIENT, "fresh", false, Instant.now());

        assertThat(repository.findById(saved.getId()).orElseThrow().isRead()).isFalse();
    }
}
