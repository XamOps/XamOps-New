package com.xammer.cloud.util;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Test.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityUtilsTest {

    @Test
    void testIsSuperAdmin() {
        ClientUserDetails superAdmin = new ClientUserDetails(
                "superadmin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")),
                1L, 1L, "tenant1"
        );
        ClientUserDetails regularUser = new ClientUserDetails(
                "user", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                1L, 2L, "tenant1"
        );

        assertTrue(SecurityUtils.isSuperAdmin(superAdmin));
        assertFalse(SecurityUtils.isSuperAdmin(regularUser));
        assertFalse(SecurityUtils.isSuperAdmin(null));
    }

    @Test
    void testIsAuthorizedForAccount() {
        Client client1 = new Client();
        client1.setId(100L);

        Client client2 = new Client();
        client2.setId(200L);

        CloudAccount account1 = new CloudAccount();
        account1.setClient(client1);

        ClientUserDetails user1 = new ClientUserDetails(
                "user1", "pass",
                Collections.emptyList(),
                100L, 1L, "tenant1"
        );

        ClientUserDetails user2 = new ClientUserDetails(
                "user2", "pass",
                Collections.emptyList(),
                200L, 2L, "tenant1"
        );

        ClientUserDetails superAdmin = new ClientUserDetails(
                "admin", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")),
                300L, 3L, "tenant1"
        );

        // Regular user authorized for their client's account
        assertTrue(SecurityUtils.isAuthorizedForAccount(account1, user1));

        // Regular user NOT authorized for another client's account
        assertFalse(SecurityUtils.isAuthorizedForAccount(account1, user2));

        // SuperAdmin authorized for any account
        assertTrue(SecurityUtils.isAuthorizedForAccount(account1, superAdmin));

        // Null checks
        assertFalse(SecurityUtils.isAuthorizedForAccount(null, user1));
        assertFalse(SecurityUtils.isAuthorizedForAccount(account1, null));
    }
}
