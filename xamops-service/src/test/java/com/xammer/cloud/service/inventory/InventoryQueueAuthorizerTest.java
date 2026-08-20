package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers automatic customer authorisation on the inventory queue.
 *
 * <p>
 * This replaced a CloudFormation parameter listing customer account ids, which does not
 * survive a growing customer base: a list someone must remember to update is one that goes
 * stale, and the failure is silent — the unauthorised customer's events are rejected and
 * nothing in XamOps reports it. The tests below pin the two properties that make the
 * automated version safe: it never widens the policy beyond connected accounts, and it
 * never replaces a working policy with one that authorises nobody.
 */
class InventoryQueueAuthorizerTest {

    private SqsClient sqsClient;
    private CloudAccountRepository cloudAccountRepository;
    private InventoryQueueAuthorizer authorizer;

    private static final String QUEUE_URL =
            "https://sqs.ap-south-1.amazonaws.com/605134457560/xamops-inventory-events.fifo";
    private static final String QUEUE_ARN =
            "arn:aws:sqs:ap-south-1:605134457560:xamops-inventory-events.fifo";
    private static final String BACKEND_ARN =
            "arn:aws:iam::605134457560:role/XamOps-App-EC2-Role";

    @BeforeEach
    void setUp() {
        sqsClient = Mockito.mock(SqsClient.class);
        cloudAccountRepository = Mockito.mock(CloudAccountRepository.class);
        SchedulingEnvironmentConfig envConfig = Mockito.mock(SchedulingEnvironmentConfig.class);
        when(envConfig.getCurrentTenantId()).thenReturn("customer1");

        authorizer = new InventoryQueueAuthorizer(sqsClient, cloudAccountRepository,
                envConfig, new ObjectMapper());
        ReflectionTestUtils.setField(authorizer, "eventsEnabled", true);
        ReflectionTestUtils.setField(authorizer, "autoAuthorize", true);
        ReflectionTestUtils.setField(authorizer, "queueUrl", QUEUE_URL);
        ReflectionTestUtils.setField(authorizer, "backendPrincipalArn", BACKEND_ARN);

        stubQueueAttributes(null);
    }

    /** GetQueueAttributes returns the ARN always, and the policy when one is supplied. */
    private void stubQueueAttributes(String policyJson) {
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenAnswer(inv -> {
                    GetQueueAttributesRequest req = inv.getArgument(0);
                    if (req.attributeNames().contains(QueueAttributeName.POLICY)) {
                        return GetQueueAttributesResponse.builder()
                                .attributes(policyJson == null ? Map.of()
                                        : Map.of(QueueAttributeName.POLICY, policyJson))
                                .build();
                    }
                    return GetQueueAttributesResponse.builder()
                            .attributes(Map.of(QueueAttributeName.QUEUE_ARN, QUEUE_ARN))
                            .build();
                });
    }

    private CloudAccount account(String awsId, String status) {
        CloudAccount a = new CloudAccount();
        a.setProvider("AWS");
        a.setAwsAccountId(awsId);
        a.setStatus(status);
        return a;
    }

    private String writtenPolicy() {
        ArgumentCaptor<SetQueueAttributesRequest> captor =
                ArgumentCaptor.forClass(SetQueueAttributesRequest.class);
        verify(sqsClient).setQueueAttributes(captor.capture());
        return captor.getValue().attributes().get(QueueAttributeName.POLICY);
    }

    // ── Authorises connected accounts ───────────────────────────────────────

    @Test
    void authorizesEveryConnectedAwsAccountWithoutAnyManualList() {
        when(cloudAccountRepository.findAll()).thenReturn(List.of(
                account("111122223333", "CONNECTED"),
                account("444455556666", "CONNECTED")));

        authorizer.syncQueuePolicy();

        String policy = writtenPolicy();
        assertTrue(policy.contains("111122223333"));
        assertTrue(policy.contains("444455556666"));
        assertTrue(policy.contains("aws:PrincipalAccount"),
                "must scope by account, not leave the queue open");
        assertTrue(policy.contains("sqs:SendMessage"));
    }

    @Test
    void scalesToManyAccountsInOneStatement() {
        // The concern that motivated this class: 100+ customers must not need 100 manual
        // edits, and must still fit one policy document.
        List<CloudAccount> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(account(String.format("%012d", 100000000000L + i), "CONNECTED"));
        }
        when(cloudAccountRepository.findAll()).thenReturn(many);

        authorizer.syncQueuePolicy();

        String policy = writtenPolicy();
        assertTrue(policy.contains("100000000000"));
        assertTrue(policy.contains("100000000149"));
        // SQS caps policy documents at 8192 bytes; 150 accounts must stay well under.
        assertTrue(policy.length() < 8192,
                "policy is " + policy.length() + " bytes, SQS limit is 8192");
    }

    @Test
    void keepsTheBackendAbleToConsumeAndToRewriteThePolicy() {
        // The policy is replaced wholesale, so omitting these would lock the backend out
        // of its own queue and out of ever fixing it.
        when(cloudAccountRepository.findAll())
                .thenReturn(List.of(account("111122223333", "CONNECTED")));

        authorizer.syncQueuePolicy();

        String policy = writtenPolicy();
        assertTrue(policy.contains("sqs:ReceiveMessage"));
        assertTrue(policy.contains("sqs:SetQueueAttributes"),
                "without this the backend cannot install future policies");
        assertTrue(policy.contains(BACKEND_ARN));
    }

    // ── Does not widen or destroy the policy ────────────────────────────────

    @Test
    void excludesAccountsThatAreNotConnected() {
        when(cloudAccountRepository.findAll()).thenReturn(List.of(
                account("111122223333", "CONNECTED"),
                account("999988887777", "PENDING"),
                account("888877776666", "FAILED")));

        authorizer.syncQueuePolicy();

        String policy = writtenPolicy();
        assertTrue(policy.contains("111122223333"));
        assertFalse(policy.contains("999988887777"), "PENDING has no working integration");
        assertFalse(policy.contains("888877776666"), "FAILED has no working integration");
    }

    @Test
    void ignoresNonAwsAccountsAndMalformedIds() {
        CloudAccount gcp = account("111122223333", "CONNECTED");
        gcp.setProvider("GCP");
        CloudAccount malformed = account("not-an-account-id", "CONNECTED");

        when(cloudAccountRepository.findAll()).thenReturn(List.of(
                gcp, malformed, account("444455556666", "CONNECTED")));

        authorizer.syncQueuePolicy();

        String policy = writtenPolicy();
        assertTrue(policy.contains("444455556666"));
        assertFalse(policy.contains("not-an-account-id"),
                "a malformed id would make the whole policy invalid");
    }

    @Test
    void leavesPolicyUntouchedWhenThereAreNoConnectedAccounts() {
        // Writing an empty principal list yields a policy that authorises nobody, which
        // looks identical to a broken deploy. Doing nothing is safer.
        when(cloudAccountRepository.findAll()).thenReturn(List.of());

        authorizer.syncQueuePolicy();

        verify(sqsClient, never()).setQueueAttributes(any(SetQueueAttributesRequest.class));
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void doesNotRewriteWhenTheAuthorisedSetIsUnchanged() {
        // SQS reorders and reformats policy documents, so comparing raw JSON would differ
        // every time and rewrite the policy on every sync. Comparison is on the account set.
        String existing = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Sid\":\"XamOpsInventoryCustomerSend\",\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"sqs:SendMessage\"],"
                + "\"Resource\":\"" + QUEUE_ARN + "\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalAccount\":"
                + "[\"111122223333\",\"444455556666\"]}}}]}";
        stubQueueAttributes(existing);
        when(cloudAccountRepository.findAll()).thenReturn(List.of(
                account("444455556666", "CONNECTED"),
                account("111122223333", "CONNECTED")));

        authorizer.syncQueuePolicy();

        verify(sqsClient, never()).setQueueAttributes(any(SetQueueAttributesRequest.class));
    }

    @Test
    void rewritesWhenANewAccountAppears() {
        String existing = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Sid\":\"XamOpsInventoryCustomerSend\",\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"sqs:SendMessage\"],"
                + "\"Resource\":\"" + QUEUE_ARN + "\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalAccount\":"
                + "[\"111122223333\"]}}}]}";
        stubQueueAttributes(existing);
        when(cloudAccountRepository.findAll()).thenReturn(List.of(
                account("111122223333", "CONNECTED"),
                account("777788889999", "CONNECTED")));

        authorizer.syncQueuePolicy();

        assertTrue(writtenPolicy().contains("777788889999"));
    }

    @Test
    void reportsTheAuthorisedSetForDiagnostics() {
        String existing = "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Sid\":\"XamOpsInventoryCustomerSend\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalAccount\":"
                + "[\"111122223333\"]}}}]}";
        stubQueueAttributes(existing);

        assertEquals(java.util.Set.of("111122223333"), authorizer.authorizedAccounts());
    }

    // ── Safety switches ─────────────────────────────────────────────────────

    @Test
    void doesNothingWhenEventsAreDisabled() {
        ReflectionTestUtils.setField(authorizer, "eventsEnabled", false);

        authorizer.syncQueuePolicy();

        Mockito.verifyNoInteractions(sqsClient);
    }

    @Test
    void doesNothingWhenAutoAuthorizeIsTurnedOff() {
        // Escape hatch for operators who want to manage the policy by hand.
        ReflectionTestUtils.setField(authorizer, "autoAuthorize", false);

        authorizer.syncQueuePolicy();

        Mockito.verifyNoInteractions(sqsClient);
    }

    @Test
    void surviveAnSqsFailureWithoutThrowing() {
        // Called from the account-connected event and from startup; throwing here would
        // break onboarding for a feature that is meant to be optional.
        when(cloudAccountRepository.findAll())
                .thenReturn(List.of(account("111122223333", "CONNECTED")));
        Mockito.doThrow(new RuntimeException("AccessDenied"))
                .when(sqsClient).setQueueAttributes(any(SetQueueAttributesRequest.class));

        authorizer.syncQueuePolicy();
    }
}
