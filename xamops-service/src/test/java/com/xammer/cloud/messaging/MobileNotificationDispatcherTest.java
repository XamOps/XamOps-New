package com.xammer.cloud.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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
    private MobileNotificationDispatcher dispatcher;

    private static final String CONTACT = "9876543210";
    private static final Map<String, String> VARS = Map.of("var1", "3");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new MessagingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://provider.example.com");
        properties.setApiKey("test-key");
        dispatcher = new MobileNotificationDispatcher(client, properties);

        // SmsWhatsAppClient never returns null by contract, so the mock must not either —
        // otherwise these tests would fail on a nullness the production client cannot produce.
        lenient().when(client.sendSms(anyString(), anyString(), anyString()))
                .thenReturn(MessageResult.sent(NotificationChannel.SMS, "+91*****10", "sms-1"));
        lenient().when(client.sendWhatsApp(anyString(), anyString(), anyMap()))
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
        when(client.sendWhatsApp(anyString(), anyString(), anyMap())).thenReturn(result);
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
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyMap());
    }

    // ── fallback semantics ───────────────────────────────────────────────────

    @Test
    void doesNotAlsoSendSmsWhenWhatsAppDeliveredAndSmsIsOnlyTheFallback() {
        enableWhatsApp();
        enableSms();
        properties.getWhatsapp().setSmsFallback(true);
        whatsAppReturns(MessageResult.sent(NotificationChannel.WHATSAPP, "+91*****10", "wa-1"));

        dispatcher.dispatch(CONTACT, VARS, MessageKind.ALERT);

        verify(client).sendWhatsApp(anyString(), anyString(), anyMap());
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

        verify(client).sendWhatsApp(anyString(), anyString(), anyMap());
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
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyMap());
    }

    @Test
    void emailOnlySelectionSendsNothingOverMobile() {
        enableWhatsApp();
        enableSms();

        List<MessageResult> results = dispatcher.dispatch(CONTACT, VARS, MessageKind.SUPPORT,
                EnumSet.of(NotificationChannel.EMAIL));

        assertThat(results).isEmpty();
        verify(client, never()).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(anyString(), anyString(), anyMap());
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
    void dispatchAllSendsNothingWhenInactive() {
        dispatcher.dispatchAll(List.of(CONTACT), VARS, MessageKind.ALERT, "acct-1");

        verify(client, never()).sendSms(anyString(), anyString(), anyString());
        verify(client, never()).sendWhatsApp(any(), any(), anyMap());
    }
}
