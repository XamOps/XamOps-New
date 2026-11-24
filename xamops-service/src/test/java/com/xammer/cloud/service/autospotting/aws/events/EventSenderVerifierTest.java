package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.events.EventSenderVerifier.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sender check (design-aws.md §9.5, AC-EVT-02): the role id part of SenderId against the stored forwarder role id. */
class EventSenderVerifierTest {

    private final ForwarderRoleLookup lookup = mock(ForwarderRoleLookup.class);
    private final AwsEventStore store = mock(AwsEventStore.class);
    private final EventSenderVerifier verifier = new EventSenderVerifier(lookup, store,
            new AwsEventProperties(false, true, "https://sqs.ap-south-1.amazonaws.com/1/xamops-autospotting-events-uat.fifo", true));

    private static CloudAccount account(String storedRoleId) {
        CloudAccount a = new CloudAccount();
        a.setId(7L);
        a.setAsForwarderRoleId(storedRoleId);
        return a;
    }

    @Test
    void theStoredRoleIdMatchesTheSenderIdBeforeTheColon() {
        assertThat(verifier.verify(account("AROAAAA"), "AROAAAA:session-1", "ap-south-1")).isEqualTo(Verdict.OK);
        verify(lookup, never()).roleId(any(), anyString(), anyString());
    }

    @Test
    void anotherRoleIsAMismatchAndNeverFallsBackToALookup() {
        assertThat(verifier.verify(account("AROAAAA"), "AROABBB:session", "ap-south-1")).isEqualTo(Verdict.MISMATCH);
        verify(lookup, never()).roleId(any(), anyString(), anyString());
    }

    @Test
    void aMissingSenderIdIsAMismatch() {
        assertThat(verifier.verify(account("AROAAAA"), null, "ap-south-1")).isEqualTo(Verdict.MISMATCH);
        assertThat(verifier.verify(account("AROAAAA"), "", "ap-south-1")).isEqualTo(Verdict.MISMATCH);
    }

    @Test
    void anUnknownIdTriggersOneLookupAndIsStoredWhenItMatches() {
        when(lookup.roleId(any(), eq("ap-south-1"), eq("-uat"))).thenReturn(Optional.of("AROAAAA"));
        CloudAccount a = account(null);
        assertThat(verifier.verify(a, "AROAAAA:s", "ap-south-1")).isEqualTo(Verdict.OK);
        verify(lookup, times(1)).roleId(any(), anyString(), anyString());
        verify(store).storeForwarderRoleId(7L, "AROAAAA");
        assertThat(a.getAsForwarderRoleId()).isEqualTo("AROAAAA");
    }

    @Test
    void aLookedUpIdThatDiffersFromTheSenderIsAMismatch() {
        when(lookup.roleId(any(), anyString(), anyString())).thenReturn(Optional.of("AROAAAA"));
        assertThat(verifier.verify(account(null), "AROAEVIL:s", "ap-south-1")).isEqualTo(Verdict.MISMATCH);
    }

    @Test
    void aFailedOrEmptyLookupRetriesTheMessage() {
        when(lookup.roleId(any(), anyString(), anyString())).thenThrow(new IllegalStateException("denied"));
        assertThat(verifier.verify(account(null), "AROAAAA:s", "ap-south-1")).isEqualTo(Verdict.RETRY);
        org.mockito.Mockito.doReturn(Optional.empty()).when(lookup).roleId(any(), anyString(), anyString());
        assertThat(verifier.verify(account(null), "AROAAAA:s", "ap-south-1")).isEqualTo(Verdict.RETRY);
        verify(store, never()).storeForwarderRoleId(anyLong(), anyString());
    }
}
