package com.xammer.cloud.service;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.TicketRepository;
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
        LocalDateTime saturday = LocalDateTime.of(2026, 6, 6, 10, 0); // Saturday
        assertThat(scheduler.isWorkingHour(saturday)).isFalse();
    }

    @Test
    void isWorkingHour_returnsFalseBeforeNineAm() {
        LocalDateTime earlyMonday = LocalDateTime.of(2026, 6, 2, 8, 59); // Monday 08:59
        assertThat(scheduler.isWorkingHour(earlyMonday)).isFalse();
    }

    @Test
    void isWorkingHour_returnsTrueMondayAtNineAm() {
        LocalDateTime monday = LocalDateTime.of(2026, 6, 2, 9, 0); // Monday 09:00
        assertThat(scheduler.isWorkingHour(monday)).isTrue();
    }

    @Test
    void isWorkingHour_returnsFalseAtSixPm() {
        LocalDateTime monday = LocalDateTime.of(2026, 6, 2, 18, 0); // Monday 18:00 (exclusive upper bound)
        assertThat(scheduler.isWorkingHour(monday)).isFalse();
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
        List<TicketReminderScheduler.PendingReminder> result = scheduler.collectAndMarkSent(now.minusMinutes(45), now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).creatorEmail).isEqualTo("user@example.com");
        assertThat(result.get(0).ticketId).isEqualTo(42L);
        assertThat(ticket.getReminderSentAt()).isNotNull();
        verify(ticketRepository).save(ticket);
    }

    @Test
    void collectAndMarkSent_skipsTicketsWithNoCreatorEmail() {
        Ticket ticket = new Ticket();
        ticket.setId(99L);
        ticket.setCreator(null);

        when(ticketRepository.findTicketsNeedingReminder(any())).thenReturn(List.of(ticket));

        List<TicketReminderScheduler.PendingReminder> result = scheduler.collectAndMarkSent(
                LocalDateTime.now().minusMinutes(45), LocalDateTime.now());

        assertThat(result).isEmpty();
        verify(ticketRepository, never()).save(any());
    }
}
