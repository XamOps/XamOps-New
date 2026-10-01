package com.xammer.cloud.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the channel decision table, which is where double-sends and silent non-sends live.
 *
 * <p>Two switches gate every channel and they mean different things: configuration says what the
 * deployment can send, the caller's allow-set says what was asked for. Getting the combination
 * wrong either bills twice for one alert or drops it entirely.
 */
class MobileNotificationDispatcherTest {

    @Mock private SmsWhatsAppClient client;

    private MessagingProperties properties;
    private MessageThrottle throttle;
    private MobileNotificationDispatcher dispatcher;

    private static final String CONTACT = "9876543210";
    private static final List<String> VARS = List.of("Production AWS", "4", "2");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new MessagingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://provider.example.com");
        properties.setApiKey("test-key");
        // No Redis in a unit test, so the throttle uses its in-process fallback — which is the
        // behaviour worth asserting anyway.
        throttle = new MessageThrottle(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(org.springframework.data.redis.core.StringRedisTemplate.class));
        dispatcher = new MobileNotificationDispatcher(client, properties, throttle);

        // SmsWhatsAppClient never returns null by contract, so the mock must not either —
        // otherwise these tests would fail on a nullness the production client cannot produce.
        lenient().when(client.sendSms(anyString(), anyString(), anyString()))
                .thenReturn(MessageResult.sent(NotificationChannel.SMS, "+91*****10", "sms-1"));
        lenient().when(client.sendWhatsApp(anyString(), anyString(), anyList()))
                .thenReturn(MessageResult.sent(NotificationChannel.WHATSAPP, "+91*****10", "wa-1"));
    }

    private void enableWhatsApp() {
        properties.getWhatsapp().setEnabled(true);
        properties.getWhatsapp().setSender("+911111111111");
        properties.getWhatsapp().setAlertTemplateId("WA_ALERT");
    }

    private void enableSms() {
        properties.getSms().setEnabled(true);
        properties.getSms().setSenderId("XAMOPS");
        properties.getSms().setAlertTemplateId("DLT_ALERT");
    }

    private void whatsAppReturns(MessageResult result) {
        when(client.sendWhatsApp(anyString(), anyString(), anyList())).thenReturn(result);
    }

    // ── the bug this table exists to prevent ─────────────────────────────────

    @Test
    void smsStillSendsWhenWhatsAppIsDisabledButFallbackFlagIsOn() {
        // sms-fallback describes WhatsApp's backup. With WhatsApp off it is meaningless, and
        // must not be read as "SMS is not a primary channel" — that would send nothing at all.
        enableSms();
        properties.getWhatsapp().setSmsFallback(true);

        dispatcher.dispatch(CONTACT, VARS, MessageKind.ALERT);

        verify(client).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyList());
    }

    // ── fallback semantics ───────────────────────────────────────────────────

    @Test
    void doesNotAlsoSendSmsWhenWhatsAppDeliveredAndSmsIsOnlyTheFallback() {
        enableWhatsApp();
        enableSms();
        properties.getWhatsapp().setSmsFallback(true);
        whatsAppReturns(MessageResult.sent(NotificationChannel.WHATSAPP, "+91*****10", "wa-1"));

        dispatcher.dispatch(CONTACT, VARS, MessageKind.ALERT);

        verify(client).sendWhatsApp(anyString(), anyString(), anyList());
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToSmsWhenWhatsAppFails() {
        enableWhatsApp();
        enableSms();
        properties.getWhatsapp().setSmsFallback(true);
        whatsAppReturns(MessageResult.failed(NotificationChannel.WHATSAPP, "+91*****10", "rejected"));

        List<MessageResult> results = dispatcher.dispatch(CONTACT, VARS, MessageKind.ALERT);

        verify(client).sendSms(anyString(), anyString(), anyString());
        assertThat(results).hasSize(2);
    }

    @Test
    void sendsBothChannelsWhenSmsIsNotConfiguredAsAFallback() {
        enableWhatsApp();
        enableSms();
        properties.getWhatsapp().setSmsFallback(false);
        whatsAppReturns(MessageResult.sent(NotificationChannel.WHATSAPP, "+91*****10", "wa-1"));

        dispatcher.dispatch(CONTACT, VARS, MessageKind.ALERT);

        verify(client).sendWhatsApp(anyString(), anyString(), anyList());
        verify(client).sendSms(anyString(), anyString(), anyString());
    }

    // ── caller allow-set ─────────────────────────────────────────────────────

    @Test
    void honoursCallerChannelSelectionOverConfiguredChannels() {
        // An admin who ticked SMS only must not get WhatsApp just because it is also configured.
        enableWhatsApp();
        enableSms();

        dispatcher.dispatch(CONTACT, VARS, MessageKind.SUPPORT,
                EnumSet.of(NotificationChannel.SMS));

        verify(client).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyList());
    }

    @Test
    void emailOnlySelectionSendsNothingOverMobile() {
        enableWhatsApp();
        enableSms();

        List<MessageResult> results = dispatcher.dispatch(CONTACT, VARS, MessageKind.SUPPORT,
                EnumSet.of(NotificationChannel.EMAIL));

        assertThat(results).isEmpty();
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyList());
    }

    // ── inactive / guard conditions ──────────────────────────────────────────

    @Test
    void isInactiveUntilCredentialsAndAChannelArePresent() {
        assertThat(dispatcher.isActive()).isFalse();   // no channel enabled yet

        enableSms();
        assertThat(dispatcher.isActive()).isTrue();

        properties.setApiKey("");
        assertThat(dispatcher.isActive()).isFalse();   // credentials incomplete

        properties.setApiKey("test-key");
        properties.setEnabled(false);
        assertThat(dispatcher.isActive()).isFalse();   // master switch off
    }

    @Test
    void dispatchAllDeduplicatesRecipientsSharingANumber() {
        enableSms();

        // Arrays.asList, not List.of — the latter rejects null elements, and tolerating a null
        // contact is exactly what is being asserted here.
        dispatcher.dispatchAll(java.util.Arrays.asList("9876543210", " 9876543210 ", null, ""),
                VARS, MessageKind.REPORT, "acct-1");

        verify(client, times(1)).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void dispatchAllHonoursPerCustomerEntitlement() {
        // Deployment can send both; this customer is only entitled to WhatsApp.
        enableWhatsApp();
        enableSms();

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1",
                EnumSet.of(NotificationChannel.WHATSAPP));

        verify(client).sendWhatsApp(anyString(), anyString(), anyList());
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void dispatchAllSendsNothingForAnUnentitledCustomer() {
        // The default state of every client. Must cost zero provider calls.
        enableWhatsApp();
        enableSms();

        List<MessageResult> results = dispatcher.dispatchAll(
                List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1",
                EnumSet.noneOf(NotificationChannel.class));

        assertThat(results).isEmpty();
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyList());
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
    }

    // ── repeat suppression ───────────────────────────────────────────────────

    @Test
    void doesNotSendTheSameAlertToTheSameNumberTwiceWithinTheCooldown() {
        // The disk scan runs every 4 hours and a volume on its threshold re-breaches most scans.
        // Without this, one stubborn disk bills six messages a day to every recipient.
        enableWhatsApp();
        properties.setCooldown(Duration.ofHours(24));

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account 123456789012");
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account 123456789012");
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account 123456789012");

        verify(client, times(1)).sendWhatsApp(anyString(), anyString(), anyList());
    }

    @Test
    void cooldownIsScopedPerAccountSoOneNoisyAccountDoesNotMuteAnother() {
        // A recipient covering several accounts must still hear about the second one.
        enableWhatsApp();
        properties.setCooldown(Duration.ofHours(24));

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account AAA");
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account BBB");

        verify(client, times(2)).sendWhatsApp(anyString(), anyString(), anyList());
    }

    @Test
    void cooldownIsScopedPerKindSoAnAlertDoesNotMuteASupportPing() {
        enableWhatsApp();
        properties.setCooldown(Duration.ofHours(24));

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account AAA");
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.SUPPORT, "account AAA");

        verify(client, times(2)).sendWhatsApp(anyString(), anyString(), anyList());
    }

    @Test
    void zeroCooldownDisablesThrottlingEntirely() {
        enableWhatsApp();
        properties.setCooldown(Duration.ZERO);

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account AAA");
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "account AAA");

        verify(client, times(2)).sendWhatsApp(anyString(), anyString(), anyList());
    }

    // ── per-kind channel allow-list ──────────────────────────────────────────

    @Test
    void withholdsAKindThatIsNotOnTheChannelAllowList() {
        // Current production setting: WhatsApp carries ALERT and SUPPORT but not REPORT, even though
        // the report template is approved and configured.
        enableWhatsApp();
        properties.getWhatsapp().setEnabledKinds(
                EnumSet.of(MessageKind.ALERT, MessageKind.SUPPORT));

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.REPORT, "acct-1");
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyList());

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1");
        verify(client, times(1)).sendWhatsApp(anyString(), anyString(), anyList());
    }

    @Test
    void anAllowListPerChannelCanDifferBetweenSmsAndWhatsApp() {
        enableWhatsApp();
        enableSms();
        properties.getWhatsapp().setSmsFallback(false);
        properties.getWhatsapp().setEnabledKinds(EnumSet.of(MessageKind.ALERT));
        properties.getSms().setEnabledKinds(EnumSet.of(MessageKind.REPORT));

        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1");

        verify(client).sendWhatsApp(anyString(), anyString(), anyList());
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void dispatchAllSendsNothingWhenInactive() {
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1");

        verify(client, never()).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(any(), any(), anyList());
    }
}
