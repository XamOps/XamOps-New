package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CloudAccount;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Credential source selection and the confused-deputy nonce guard (design-gcp.md §10.4). Keyless stays off. */
class GcpClientProviderModeTest {
    @Test
    void existingAndNewAccountsReadAsKeyMode() {
        CloudAccount a = new CloudAccount();
        assertThat(a.getGcpAuthMode()).isEqualTo("KEY");
        assertThat(GcpClientProvider.authModeOf(a)).isEqualTo(GcpAuthMode.KEY);
        a.setGcpAuthMode(null);
        assertThat(GcpClientProvider.authModeOf(a)).isEqualTo(GcpAuthMode.KEY);
        assertThat(GcpClientProvider.authModeOf(null)).isEqualTo(GcpAuthMode.KEY);
        a.setGcpAuthMode("impersonation");
        assertThat(GcpClientProvider.authModeOf(a)).isEqualTo(GcpAuthMode.IMPERSONATION);
    }

    @Test
    void aNonceMismatchRefusesAnImpersonationConnect() {
        assertThat(GcpClientProvider.nonceMatches("abc123", "created by XamOps xamops-connect:abc123")).isTrue();
        assertThat(GcpClientProvider.nonceMatches("abc123", "xamops-connect:zzz999")).isFalse();
        assertThat(GcpClientProvider.nonceMatches("abc123", null)).isFalse();
        assertThat(GcpClientProvider.nonceMatches(null, "xamops-connect:abc123")).isFalse();
        assertThat(GcpClientProvider.nonceMatches("", "xamops-connect:")).isFalse();
    }
}
