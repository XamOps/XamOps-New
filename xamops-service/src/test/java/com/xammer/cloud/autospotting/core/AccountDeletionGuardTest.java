package com.xammer.cloud.autospotting.core;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard;
import com.xammer.cloud.service.autospotting.core.AccountDeletionGuard.Check;
import com.xammer.cloud.service.autospotting.core.AccountHasManagedGroupsException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
/** PO-21 and PO-21a: which group states block account deletion, and which are listed as still converted. */
class AccountDeletionGuardTest {
    private static final long ACCOUNT = 7L;
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);
    private final AccountDeletionGuard guard = new AccountDeletionGuard(groups);
    private SpotGroup group(String key, GroupState state) {
        SpotGroup g = new SpotGroup();
        g.setId(UUID.randomUUID());
        g.setGroupKey(key);
        g.setState(state);
        return g;
    }
    private void rows(SpotGroup... rows) {
        when(groups.findByCloudAccountId(ACCOUNT)).thenReturn(new ArrayList<>(List.of(rows)));
    }
    @Test
    void applyingActiveFallbackAndRevertingBlock() {
        for (GroupState s : List.of(GroupState.APPLYING, GroupState.ACTIVE, GroupState.FALLBACK,
                GroupState.REVERTING)) {
            rows(group("us-east-1/a", s));
            assertThatThrownBy(() -> guard.assertDeletable(ACCOUNT))
                    .as(s.name()).isInstanceOf(AccountHasManagedGroupsException.class);
        }
    }
    @Test
    void exceptionNamesTheBlockingGroupsAndTheRevertAllLink() {
        rows(group("us-east-1/b", GroupState.ACTIVE), group("us-east-1/a", GroupState.FALLBACK),
                group("us-east-1/c", GroupState.REVERTED));
        AccountHasManagedGroupsException e = org.junit.jupiter.api.Assertions.assertThrows(
                AccountHasManagedGroupsException.class, () -> guard.assertDeletable(ACCOUNT));
        assertThat(e.getGroups()).extracting("groupKey").containsExactly("us-east-1/a", "us-east-1/b");
        assertThat(e.getGroups()).extracting("state").containsExactly("FALLBACK", "ACTIVE");
        assertThat(e.getRevertAllPath()).isEqualTo("/autospotting/accounts/7/revert-all");
    }
    @Test
    void degradedPausedAndFailedDoNotBlockButAreListedAsStillConverted() {
        rows(group("us-east-1/d", GroupState.DEGRADED), group("us-east-1/p", GroupState.PAUSED),
                group("us-east-1/f", GroupState.FAILED));
        assertThatCode(() -> guard.assertDeletable(ACCOUNT)).doesNotThrowAnyException();
        Check check = guard.check(ACCOUNT);
        assertThat(check.deletable()).isTrue();
        assertThat(check.blocking()).isEmpty();
        assertThat(check.stillConverted()).extracting("state").containsExactlyInAnyOrder("DEGRADED", "PAUSED",
                "FAILED");
    }
    @Test
    void unmanagedRevertedGoneAndNoGroupsAreDeletable() {
        rows(group("us-east-1/u", GroupState.UNMANAGED), group("us-east-1/r", GroupState.REVERTED),
                group("us-east-1/g", GroupState.GONE));
        assertThat(guard.check(ACCOUNT).deletable()).isTrue();
        assertThat(guard.check(ACCOUNT).stillConverted()).isEmpty();
        rows();
        assertThatCode(() -> guard.assertDeletable(ACCOUNT)).doesNotThrowAnyException();
    }
    @Test
    void theGuardOnlyReads() {
        rows(group("us-east-1/a", GroupState.ACTIVE));
        guard.check(ACCOUNT);
        verify(groups).findByCloudAccountId(ACCOUNT);
        verifyNoMoreInteractions(groups);
    }
}
