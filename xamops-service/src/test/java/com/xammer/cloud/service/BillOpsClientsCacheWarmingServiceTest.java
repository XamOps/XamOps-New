package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.dto.billops.BillOpsClientDetailsDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.service.billops.BillOpsClientListService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The warmer rewrites the client list every 4 minutes, so whatever it caches is what the Accounts
 * page shows. It used to cache a list from its own builder that had no Upwind fields, which made
 * every client's Security column read "n/a".
 */
@ExtendWith(MockitoExtension.class)
class BillOpsClientsCacheWarmingServiceTest {

    @Mock private ClientRepository clientRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private BillOpsClientListService clientListService;

    @InjectMocks private BillOpsClientsCacheWarmingService warmer;

    @Test
    @DisplayName("Caches the shared builder's list under the key the Accounts page is served from")
    void cachesTheSharedBuildersList() {
        Client client = new Client("Default Client");
        BillOpsClientDetailsDto row = new BillOpsClientDetailsDto();
        row.setUpwindEligibleAccounts(5);
        row.setUpwindConnectedAccounts(2);
        List<BillOpsClientDetailsDto> rows = List.of(row);
        when(clientRepository.findAll()).thenReturn(List.of(client));
        when(clientListService.buildFromCacheOnly(List.of(client))).thenReturn(rows);

        warmer.refreshClientsCache();

        // The literal key, not the constant: BillOpsAdminController reads it and the billops
        // module evicts it by prefix, so a rename has to break this test rather than the page.
        verify(redisCacheService).put("billops:admin:clients", rows, 5);
        verify(clientListService, never()).build(any());
    }

    @Test
    @DisplayName("Leaves the cache alone when there are no clients")
    void noClientsNothingCached() {
        when(clientRepository.findAll()).thenReturn(List.of());

        warmer.refreshClientsCache();

        verify(redisCacheService, never()).put(any(), any(), anyInt());
    }
}
