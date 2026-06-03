package com.xammer.billops.service;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.billops.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TicketReminderSchedulerTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private EmailService emailService;

    private TicketReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new TicketReminderScheduler(ticketRepository, emailService);
    }

    @Test
    void isWorkingHour_returnsFalseOnWeekend() {
        assertThat(scheduler.isWorkingHour(LocalDateTime.of(2026, 6, 6, 10, 0))).isFalse();
    }

    @Test
    void isWorkingHour_returnsFalseBeforeNineAm() {
        assertThat(scheduler.isWorkingHour(LocalDateTime.of(2026, 6, 2, 8, 59))).isFalse();
    }

    @Test
    void isWorkingHour_returnsTrueMondayAtNineAm() {
        assertThat(scheduler.isWorkingHour(LocalDateTime.of(2026, 6, 2, 9, 0))).isTrue();
    }

    @Test
    void isWorkingHour_returnsFalseAtSixPm() {
        assertThat(scheduler.isWorkingHour(LocalDateTime.of(2026, 6, 2, 18, 0))).isFalse();
    }

    @Test
    void collectAndMarkSent_setsReminderSentAtAndReturnsReminders() {
        User creator = new User();
        creator.setEmail("user@example.com");
        creator.setUsername("testuser");

        Ticket ticket = new Ticket();
        ticket.setId(42L);
        ticket.setSubject("Help with billing");
        ticket.setCreator(creator);
        ticket.setLastAdminReplyAt(LocalDateTime.now().minusHours(1));

        when(ticketRepository.findTicketsNeedingReminder(any())).thenReturn(List.of(ticket));
        when(ticketRepository.save(any())).thenReturn(ticket);

        LocalDateTime now = LocalDateTime.now();
        List<TicketReminderScheduler.PendingReminder> result =
                scheduler.collectAndMarkSent(now.minusMinutes(45), now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).creatorEmail).isEqualTo("user@example.com");
        assertThat(ticket.getReminderSentAt()).isNotNull();
        verify(ticketRepository).save(ticket);
    }

    @Test
    void collectAndMarkSent_skipsTicketsWithNoCreatorEmail() {
        Ticket ticket = new Ticket();
        ticket.setId(99L);
        ticket.setCreator(null);

        when(ticketRepository.findTicketsNeedingReminder(any())).thenReturn(List.of(ticket));

        List<TicketReminderScheduler.PendingReminder> result =
                scheduler.collectAndMarkSent(LocalDateTime.now().minusMinutes(45), LocalDateTime.now());

        assertThat(result).isEmpty();
        verify(ticketRepository, never()).save(any());
    }
}
