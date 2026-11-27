package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/** DLQ listing (no bodies), replay by move task, the FIFO re-send fallback and one replay at a time. */
public class AwsEventDlqServiceTest {

    private final FakeSqsClient sqs = new FakeSqsClient();
    private final AutoSpottingAuditWriter audit = mock(AutoSpottingAuditWriter.class);
    private AwsEventDlqService service;

    @BeforeEach
    void setUp() {
        service = new AwsEventDlqService(sqs, new AwsEventProperties(false, true, FakeSqsClient.QUEUE_URL, true),
                new ObjectMapper(), audit, txManager());
        sqs.dlq.add(msg("e1", "acct-111111111111-ap-south-1"));
        sqs.dlq.add(msg("e2", "acct-222222222222-ap-south-1"));
    }

    /** A transaction manager that hands out a status, as a real one would; the audit row is not under test here. */
    public static org.springframework.transaction.PlatformTransactionManager txManager() {
        var tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(tm.getTransaction(any())).thenReturn(
                new org.springframework.transaction.support.SimpleTransactionStatus());
        return tm;
    }
    private FakeSqsClient.Msg msg(String id, String group) {
        sqs.send(AwsEventEnvelopeTest.body(id, AwsEventEnvelope.INTERRUPTION, group.substring(5, 17), "{}"),
                group, id, "AROAX:s");
        return sqs.queue.remove(sqs.queue.size() - 1);
    }

    @Test
    void theSummaryListsDepthAndMessagesWithoutBodies() {
        AwsEventDlqService.Summary s = service.summary();
        assertThat(s.configured()).isTrue();
        assertThat(s.depth()).isEqualTo(2);
        assertThat(s.messages()).hasSize(2);
        assertThat(s.messages().get(0).detailType()).isEqualTo(AwsEventEnvelope.INTERRUPTION);
        assertThat(s.messages().get(0).receiveCount()).isEqualTo(1);
        assertThat(s.toString()).doesNotContain("instance-id").doesNotContain("\"detail\"");
        assertThat(sqs.dlq).hasSize(2); // peeked, not consumed
    }

    @Test
    void redriveStartsAMoveTaskAndAuditsTheReason() {
        AwsEventDlqService.RedriveResult r = service.redrive("replay after fix", Actor.system());
        assertThat(r.mode()).isEqualTo("MOVE_TASK");
        assertThat(sqs.dlq).isEmpty();
        assertThat(sqs.queue).hasSize(2);
        verify(audit).write(any());
    }

    @Test
    void aRunningMoveTaskIs409RedriveInProgress() {
        sqs.moveTaskRunning = true;
        assertThatThrownBy(() -> service.redrive("again", Actor.system()))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REDRIVE_IN_PROGRESS"));
        assertThat(sqs.moveTasksStarted).isZero();
    }

    @Test
    void whenFifoMoveTasksAreUnsupportedItResendsWithTheOriginalGroupAndAFreshDedupId() {
        sqs.moveTaskUnsupported = true;
        AwsEventDlqService.RedriveResult r = service.redrive("v-aws-14", Actor.system());
        assertThat(r.mode()).isEqualTo("RESEND");
        assertThat(r.moved()).isEqualTo(2);
        assertThat(sqs.dlq).isEmpty();
        assertThat(sqs.sent).extracting(s -> s.messageGroupId())
                .containsExactly("acct-111111111111-ap-south-1", "acct-222222222222-ap-south-1");
        assertThat(sqs.sent).extracting(s -> s.messageDeduplicationId()).doesNotContain("e1", "e2");
    }

    @Test
    void aRefusedQueueIsNeverListedOrReplayed() {
        AwsEventDlqService prodInUat = new AwsEventDlqService(sqs, new AwsEventProperties(false, true,
                "https://sqs.ap-south-1.amazonaws.com/1/xamops-autospotting-events.fifo", true), new ObjectMapper(), audit, txManager());
        assertThat(prodInUat.summary().configured()).isFalse();
        assertThatThrownBy(() -> prodInUat.redrive("x", Actor.system())).isInstanceOf(AutoSpottingApiException.class);
        assertThat(sqs.calls).isEmpty();
    }
}
