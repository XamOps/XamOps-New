package com.xammer.cloud.autospotting.notify;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.NotificationCategory;
import com.xammer.cloud.service.NotificationRecipientResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The recipient overload of design-ux-ops.md section 7.3: SECURITY keeps today's behaviour for every existing
 * caller, AUTOSPOTTING reads the new switch only, AUTOSPOTTING_MANDATORY ignores both (PO-24).
 */
class NotificationRecipientCategoryTest {

    private final UserRepository users = mock(UserRepository.class);
    private final NotificationRecipientResolver resolver = new NotificationRecipientResolver(users);

    private static User user(String email, boolean security, boolean autospotting) {
        User u = new User();
        u.setEmail(email);
        u.setEmailNotificationsEnabled(security);
        u.setAutospottingEmailEnabled(autospotting);
        return u;
    }

    private void fixtures() {
        when(users.findByClientId(7L)).thenReturn(List.of(
                user("security-muted@x.test", false, true),
                user("as-muted@x.test", true, false),
                user("both-muted@x.test", false, false),
                user("open@x.test", true, true)));
    }

    @Test
    void securityIsTodaysBehaviour_andTheOneArgumentMethodsAreUnchanged() {
        fixtures();
        assertThat(resolver.emailsForClient(7L)).containsExactly("as-muted@x.test", "open@x.test");
        assertThat(resolver.emailsForClient(7L, NotificationCategory.SECURITY))
                .containsExactly("as-muted@x.test", "open@x.test");
        assertThat(resolver.emailsForClient(7L, null)).containsExactly("as-muted@x.test", "open@x.test");
    }

    @Test
    void autospottingReadsOnlyTheAutoSpottingSwitch() {
        fixtures();
        assertThat(resolver.emailsForClient(7L, NotificationCategory.AUTOSPOTTING))
                .containsExactly("security-muted@x.test", "open@x.test");
    }

    @Test
    void mandatoryIgnoresBothSwitches() {
        fixtures();
        assertThat(resolver.emailsForClient(7L, NotificationCategory.AUTOSPOTTING_MANDATORY))
                .containsExactly("security-muted@x.test", "as-muted@x.test", "both-muted@x.test", "open@x.test");
    }

    @Test
    void anAccountOverloadResolvesTheClient_andAnAccountWithoutOneIsEmpty() {
        fixtures();
        CloudAccount account = new CloudAccount();
        Client client = new Client();
        client.setId(7L);
        account.setClient(client);
        assertThat(resolver.emailsForAccount(account, NotificationCategory.AUTOSPOTTING_MANDATORY)).hasSize(4);
        assertThat(resolver.emailsForAccount(new CloudAccount(), NotificationCategory.AUTOSPOTTING)).isEmpty();
        assertThat(resolver.emailsForAccount(null, NotificationCategory.AUTOSPOTTING)).isEmpty();
    }

    @Test
    void aLookupFailureYieldsNobodyRatherThanThrowing() {
        when(users.findByClientId(7L)).thenThrow(new IllegalStateException("db"));
        assertThat(resolver.emailsForClient(7L, NotificationCategory.AUTOSPOTTING_MANDATORY)).isEmpty();
    }
}
