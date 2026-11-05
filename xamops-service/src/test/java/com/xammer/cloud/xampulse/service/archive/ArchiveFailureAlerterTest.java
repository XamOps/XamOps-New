package com.xammer.cloud.xampulse.service.archive;

import com.xammer.cloud.xampulse.domain.XpAccountConfig;
import com.xammer.cloud.xampulse.domain.XpChannel;
import com.xammer.cloud.xampulse.repository.XpChannelRepository;
import com.xammer.cloud.xampulse.service.alert.NotificationRouter;
import com.xammer.cloud.xampulse.service.alert.channel.Notification;
import com.xammer.cloud.xampulse.service.insight.EventWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ArchiveFailureAlerterTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:30:00Z");

    private final EventWriter events = mock(EventWriter.class);
    private final NotificationRouter router = mock(NotificationRouter.class);
    private final XpChannelRepository channels = mock(XpChannelRepository.class);
    private final DefaultArchiveFailureAlerter alerter =
            new DefaultArchiveFailureAlerter(events, router, channels, Clock.fixed(NOW, ZoneOffset.UTC));

    private XpAccountConfig cfg() {
        XpAccountConfig c = new XpAccountConfig();
        c.setCloudAccountId(7L);
        return c;
    }

    private XpChannel channel(long id) {
        XpChannel ch = mock(XpChannel.class);
        when(ch.getId()).thenReturn(id);
        return ch;
    }

    @Test
    void writesAnAlertEventAndNotifiesTheAccountChannels() throws Exception {
        XpChannel c1 = channel(11L);
        XpChannel c2 = channel(12L);
        when(channels.findByCloudAccountId(7L)).thenReturn(List.of(c1, c2));
        alerter.archiveFailing(cfg(), 3, "IllegalStateException");

        verify(events).record(7L, "ALERT", "archive", "Archive writer failing", "3 runs in a row failed", NOW);
        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(router).route(eq(7L), eq(0L), eq("archive"), any(), eq(List.of(11L, 12L)), n.capture(), eq(false), eq(NOW));
        assertEquals("FIRED", n.getValue().kind());
        assertFalse(n.getValue().body().contains("IllegalStateException"), "no exception detail in the message");
    }

    @Test
    void anEventFailureDoesNotStopTheNotification() throws Exception {
        doThrow(new SQLException("clickhouse down")).when(events).record(anyLong(), any(), any(), any(), any(), any());
        XpChannel c1 = channel(11L);
        when(channels.findByCloudAccountId(7L)).thenReturn(List.of(c1));
        assertDoesNotThrow(() -> alerter.archiveFailing(cfg(), 3, "X"));
        verify(router).route(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aRouterFailureIsSwallowed() throws Exception {
        when(channels.findByCloudAccountId(7L)).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> alerter.archiveFailing(cfg(), 3, "X"));
        verify(events).record(anyLong(), any(), any(), any(), any(), any());
    }
}
