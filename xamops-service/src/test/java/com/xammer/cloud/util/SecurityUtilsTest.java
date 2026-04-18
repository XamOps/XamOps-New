package com.xammer.cloud.util;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityUtilsTest {

    @Test
    void testIsAuthorizedForAccount_SuperAdmin() {
        ClientUserDetails admin = new ClientUserDetails("admin", "pass",
            List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")), 1L, 1L, "tenant1");

        CloudAccount account = new CloudAccount();
        Client client = new Client();
        client.setId(2L); // Different client
        account.setClient(client);

        assertTrue(SecurityUtils.isAuthorizedForAccount(account, admin));
    }

    @Test
    void testIsAuthorizedForAccount_SameClient() {
        ClientUserDetails user = new ClientUserDetails("user", "pass",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 1L, 1L, "tenant1");

        CloudAccount account = new CloudAccount();
        Client client = new Client();
        client.setId(1L); // Same client
        account.setClient(client);

        assertTrue(SecurityUtils.isAuthorizedForAccount(account, user));
    }

    @Test
    void testIsAuthorizedForAccount_DifferentClient() {
        ClientUserDetails user = new ClientUserDetails("user", "pass",
            List.of(new SimpleGrantedAuthority("ROLE_USER")), 1L, 1L, "tenant1");

        CloudAccount account = new CloudAccount();
        Client client = new Client();
        client.setId(2L); // Different client
        account.setClient(client);

        assertFalse(SecurityUtils.isAuthorizedForAccount(account, user));
    }

    @Test
    void testIsAuthorizedForAccount_NullValues() {
        assertFalse(SecurityUtils.isAuthorizedForAccount(null, null));

        ClientUserDetails user = new ClientUserDetails("user", "pass",
            Collections.emptyList(), 1L, 1L, "tenant1");
        assertFalse(SecurityUtils.isAuthorizedForAccount(null, user));

        CloudAccount account = new CloudAccount();
        assertFalse(SecurityUtils.isAuthorizedForAccount(account, null));
    }
}
