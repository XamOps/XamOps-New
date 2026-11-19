package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.converter.EncryptedStringConverter;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.gcp.GcpAccessValidator;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Connect validation stores the access type; a missing read permission saves nothing; a duplicate project is a 409 case. All clients are mocks. */
class GcpCredentialStorageTest {
    private static final String KEY = "{\"client_email\":\"xamops@p1.iam.gserviceaccount.com\"}";
    private final GcpClientProvider provider = mock(GcpClientProvider.class);
    private final CloudAccountRepository repo = mock(CloudAccountRepository.class);
    private final GcpDataService service = new GcpDataService(provider, null, null, null, null, repo, null, null, null,
            new ObjectMapper(), new GcpAccessValidator(provider));

    private GcpAccountRequestDto request() {
        GcpAccountRequestDto r = new GcpAccountRequestDto();
        r.setAccountName("p1");
        r.setProjectId("p1");
        r.setServiceAccountKey(KEY);
        return r;
    }

    private void granted(Set<String> perms) throws IOException {
        when(provider.validateServiceAccountAccess(anyString(), anyString())).thenReturn(Optional.empty());
        when(provider.testProjectPermissions(anyString(), anyString(), any())).thenReturn(perms);
        when(repo.existsByGcpProjectId(anyString())).thenReturn(false);
        when(repo.save(any(CloudAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Set<String> perms(boolean autoSpotting, boolean readWrite) {
        Set<String> s = new HashSet<>(GcpAccessValidator.READ_SET);
        if (autoSpotting) {
            s.addAll(GcpAccessValidator.AUTOSPOTTING_SET);
        }
        if (readWrite) {
            s.add(GcpAccessValidator.READ_WRITE_MARKER);
        }
        return s;
    }

    @Test
    void eachPermissionSubsetStoresTheRightTypeInKeyMode() throws IOException {
        Client c = new Client();
        granted(perms(false, false));
        assertThat(service.createGcpAccount(request(), c).getGcpAccessType()).isEqualTo("READ_ONLY");
        granted(perms(true, false));
        CloudAccount as = service.createGcpAccount(request(), c);
        assertThat(as.getGcpAccessType()).isEqualTo("AUTOSPOTTING");
        assertThat(as.getGcpAuthMode()).isEqualTo("KEY");
        granted(perms(true, true));
        assertThat(service.createGcpAccount(request(), c).getGcpAccessType()).isEqualTo("READ_WRITE");
    }

    @Test
    void aMissingReadPermissionRefusesTheConnectAndSavesNothing() throws IOException {
        Set<String> p = perms(true, false);
        p.remove("compute.instanceTemplates.get");
        granted(p);
        assertThatThrownBy(() -> service.createGcpAccount(request(), new Client()))
                .hasMessageContaining("compute.instanceTemplates.get");
        verify(repo, never()).save(any());
    }

    @Test
    void aProjectThatIsAlreadyRegisteredIsRefused() throws IOException {
        granted(perms(true, false));
        when(repo.existsByGcpProjectId(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.createGcpAccount(request(), new Client()))
                .isInstanceOf(GcpProjectAlreadyConnectedException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void keyStoredEncrypted() throws NoSuchFieldException {
        Convert convert = CloudAccount.class.getDeclaredField("gcpServiceAccountKey").getAnnotation(Convert.class);
        assertThat(convert).isNotNull();
        assertThat(convert.converter()).isEqualTo(EncryptedStringConverter.class);
    }

    @Test
    void anExistingProjectWithANullAccessTypeStillCountsAsKeyModeAndUnchecked() {
        CloudAccount existing = new CloudAccount();
        assertThat(existing.getGcpAccessType()).isNull();
        assertThat(GcpClientProvider.authModeOf(existing)).isEqualTo(GcpAuthMode.KEY);
    }
}
