package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one thing {@code DELETE /clients/{id}} must never do is take an admin's "remove this empty
 * client" and turn it into a cascade that deletes users, accounts and invoices. {@link Client}
 * cascades {@code ALL}/{@code orphanRemoval} to both relations, so the guard — refuse with 409
 * while the client still has either — is the whole safety of the endpoint. These tests pin that:
 * a client with users is refused, a client with accounts is refused, and the delete only fires
 * when the client is genuinely empty.
 *
 * <p>Unit-level rather than through MockMvc: the controller uses field injection with ~15
 * collaborators, but this handler touches only four, and calling it directly lets the 409 body and
 * the "delete was never reached" / "both cache tiers evicted" facts be asserted without standing up
 * the whole bean graph. The other field-injected controller tests here use the same reflection
 * injection ({@code LoginOtpControllerTest}, {@code GoogleAuthControllerTest}).
 */
@ExtendWith(MockitoExtension.class)
class BillOpsAdminControllerDeleteClientTest {

    private static final long CLIENT_ID = 42L;
    private static final String CLIENTS_CACHE_KEY = "billops:admin:clients";

    @Mock private ClientRepository clientRepository;
    @Mock private UserRepository userRepository;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private RedisCacheService redisCacheService;

    private BillOpsAdminController controller;
    private Client client;

    @BeforeEach
    void setUp() {
        controller = new BillOpsAdminController();
        ReflectionTestUtils.setField(controller, "clientRepository", clientRepository);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "cloudAccountRepository", cloudAccountRepository);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);

        client = new Client("Acme Corp");
        client.setId(CLIENT_ID);
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));
    }

    @Test
    @DisplayName("A client with users is refused with 409 and is never deleted")
    void clientWithUsersIsRefused() {
        when(userRepository.countByClientId(CLIENT_ID)).thenReturn(3L);
        when(cloudAccountRepository.countByClientId(CLIENT_ID)).thenReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.deleteClient(CLIENT_ID);

        assertEquals(409, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(3L, body.get("userCount"));
        assertEquals(0L, body.get("accountCount"));
        assertTrue(String.valueOf(body.get("message")).contains("Acme Corp"));
        // The guarantee the whole endpoint exists to make: a non-empty client is not cascaded away.
        verify(clientRepository, never()).delete(client);
        verify(redisCacheService, never()).evict(CLIENTS_CACHE_KEY);
    }

    @Test
    @DisplayName("A client with cloud accounts but no users is refused with 409 and is never deleted")
    void clientWithCloudAccountsIsRefused() {
        when(userRepository.countByClientId(CLIENT_ID)).thenReturn(0L);
        when(cloudAccountRepository.countByClientId(CLIENT_ID)).thenReturn(2L);

        ResponseEntity<Map<String, Object>> response = controller.deleteClient(CLIENT_ID);

        assertEquals(409, response.getStatusCode().value());
        assertEquals(2L, response.getBody().get("accountCount"));
        verify(clientRepository, never()).delete(client);
        verify(redisCacheService, never()).evict(CLIENTS_CACHE_KEY);
    }

    @Test
    @DisplayName("An empty client is deleted and both cache tiers are evicted")
    void emptyClientIsDeleted() {
        when(userRepository.countByClientId(CLIENT_ID)).thenReturn(0L);
        when(cloudAccountRepository.countByClientId(CLIENT_ID)).thenReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.deleteClient(CLIENT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("deleted"));
        verify(clientRepository).delete(client);
        verify(redisCacheService).evict(CLIENTS_CACHE_KEY);
        verify(redisCacheService).evictByPattern(CLIENTS_CACHE_KEY + ":sub:*");
    }
}
