package com.xammer.cloud.controller.autospotting;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.autospotting.v2.StackCapabilityDto;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class StackCapabilityControllerTest {
    private static CloudAccount account(String accessType, String version) {
        CloudAccount a = new CloudAccount("Acme", "ext-1", accessType, mock(Client.class));
        a.setStackTemplateVersion(version);
        return a;
    }
    @Test
    void anOldReadWriteStackNamesWhatIsMissing() {
        StackCapabilityDto dto = StackCapabilityController.describe(account("read-write", null));
        assertThat(dto.applicable()).isTrue();
        assertThat(dto.updateRequired()).isTrue();
        assertThat(dto.missing()).extracting(StackCapabilityDto.Missing::code)
                .contains("AS_EVENTS_MULTI_REGION", "AS_PASSROLE_SCOPED", "AS_SIMULATE_SELF", "AS_QUOTA_READS");
        assertThat(dto.latestVersion()).isEqualTo("rw-2026.11.2");
    }
    @Test
    void aStackWithoutTheEventTagLookupIsPromptedToUpdate() {
        StackCapabilityDto dto = StackCapabilityController.describe(account("read-write", "rw-2026.11.1"));
        assertThat(dto.updateRequired()).isTrue();
        assertThat(dto.missing()).extracting(StackCapabilityDto.Missing::code).containsExactly("AS_EVENTS_TAG_LOOKUP");
    }
    @Test
    void aCurrentStackNeedsNoUpdate() {
        StackCapabilityDto dto = StackCapabilityController.describe(account("read-write", "rw-2026.11.2"));
        assertThat(dto.updateRequired()).isFalse();
        assertThat(dto.missing()).isEmpty();
    }
    @Test
    void aReadOnlyConnectionHasNoStackUpdateForThisPurpose() {
        StackCapabilityDto dto = StackCapabilityController.describe(account("read-only", null));
        assertThat(dto.applicable()).isFalse();
        assertThat(dto.updateRequired()).isFalse();
    }
}
