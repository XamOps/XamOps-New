package com.xammer.cloud.service.email;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketEmailMessage;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.TicketEmailMessageRepository;
import com.xammer.cloud.repository.TicketRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.FileStorageService;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailTicketIngestServiceTest {

    private static final String TECHNICAL = "techtickets@xamops.com";
    private static final String BILLING = "bizfin@xammer.com";
    private static final String SELF = "notifications@xamops.com";

    @Mock private TicketRepository ticketRepository;
    @Mock private TicketEmailMessageRepository emailMessageRepository;
    @Mock private UserRepository userRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private RedisCacheService redisCacheService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private EmailTicketIngestService service;
    private long nextTicketId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new EmailTicketIngestService(ticketRepository, emailMessageRepository, userRepository,
                clientRepository, fileStorageService, redisCacheService, messagingTemplate);

        ReflectionTestUtils.setField(service, "technicalAdminEmailsCsv", TECHNICAL);
        ReflectionTestUtils.setField(service, "billingAdminEmail", BILLING);
        ReflectionTestUtils.setField(service, "selfEmail", SELF);
        ReflectionTestUtils.setField(service, "ignoredSendersCsv", "");
        ReflectionTestUtils.setField(service, "internalDomainsCsv", "xamops.com,xammer.com,xammer.in");
        ReflectionTestUtils.setField(service, "intakeClientName", "Email Intake");

        nextTicketId = 100L;
        when(emailMessageRepository.existsByMessageId(anyString())).thenReturn(false);
        when(emailMessageRepository.findByMessageIdInWithTicket(any())).thenReturn(List.of());
        when(emailMessageRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findByEmailIgnoreCaseOrderByIdAsc(anyString())).thenReturn(List.of());
        when(userRepository.findByEmailEndingWithIgnoreCaseOrderByIdAsc(anyString())).thenReturn(List.of());
        when(ticketRepository.save(any())).thenAnswer(i -> {
            Ticket ticket = i.getArgument(0);
            if (ticket.getId() == null) {
                ticket.setId(nextTicketId++);
            }
            return ticket;
        });
        Client intake = client(9L, "Email Intake");
        when(clientRepository.findByName("Email Intake")).thenReturn(Optional.of(intake));
    }

    // ------------------------------------------------------------------ routing

    @Test
    void aCcOnTheTechnicalAddressOpensATechnicalTicket() {
        // The case this feature exists for: the group is copied on a conversation between other
        // people, not addressed directly.
        service.ingest(mail("priya@customer.com", List.of("colleague@customer.com"), List.of(TECHNICAL)));

        assertThat(savedTicket().getCategory()).isEqualTo("Technical");
    }

    @Test
    void theBillingAddressRoutesToTheBillingQueue() {
        service.ingest(mail("priya@customer.com", List.of(BILLING), List.of()));

        assertThat(savedTicket().getCategory()).isEqualTo("Account and Billing");
    }

    @Test
    void aMailToBothAddressesGoesToBilling() {
        // Billing questions copied to the technical list must still reach the team that can act.
        service.ingest(mail("priya@customer.com", List.of(TECHNICAL), List.of(BILLING)));

        assertThat(savedTicket().getCategory()).isEqualTo("Account and Billing");
    }

    @Test
    void aMailMentioningNeitherAddressOpensNothing() {
        EmailTicketIngestService.Result result =
                service.ingest(mail("priya@customer.com", List.of("someone@else.com"), List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_SKIPPED_NO_MATCH);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void matchesTheGroupAddressRegardlessOfHeaderCase() {
        ParsedEmail mail = new ParsedEmail("mixed@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of("TechTickets@XamOps.com".toLowerCase()), List.of(),
                "Case test", LocalDateTime.now(), "body", false, false, List.of());

        assertThat(service.mentionedGroupAddress(mail)).isEqualTo(TECHNICAL);
    }

    // ------------------------------------------------------------------ loops and noise

    @Test
    void ourOwnNotificationComingBackIsNotATicket() {
        // Every ticket mails the group from notifications@. With the ingest mailbox subscribed to
        // that group the notification returns here — unfiltered, each ticket would breed the next.
        EmailTicketIngestService.Result result =
                service.ingest(mail(SELF, List.of(TECHNICAL), List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_SKIPPED_LOOP);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mailFromTheGroupAddressItselfIsNotATicket() {
        EmailTicketIngestService.Result result =
                service.ingest(mail(TECHNICAL, List.of(TECHNICAL), List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_SKIPPED_LOOP);
    }

    @Test
    void anOutOfOfficeIsNotATicket() {
        ParsedEmail autoReply = new ParsedEmail("ooo@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of(TECHNICAL), List.of(),
                "Out of office", LocalDateTime.now(), "Away until Monday", false, true, List.of());

        EmailTicketIngestService.Result result = service.ingest(autoReply);

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_SKIPPED_AUTO_REPLY);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void anAlreadyIngestedMessageIsNotProcessedTwice() {
        when(emailMessageRepository.existsByMessageId("seen@customer.com")).thenReturn(true);

        ParsedEmail mail = new ParsedEmail("seen@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of(TECHNICAL), List.of(),
                "Repeat", LocalDateTime.now(), "body", false, false, List.of());

        service.ingest(mail);

        verify(ticketRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ sender → client

    @Test
    void aRegisteredSenderBecomesTheCreatorAndOwnsTheTicketAsTheirClient() {
        Client acme = client(1L, "Acme");
        when(userRepository.findByEmailIgnoreCaseOrderByIdAsc("priya@customer.com"))
                .thenReturn(List.of(user(7L, "priya", "priya@customer.com", acme)));

        service.ingest(mail("priya@customer.com", List.of(TECHNICAL), List.of()));

        Ticket ticket = savedTicket();
        assertThat(ticket.getClient()).isEqualTo(acme);
        assertThat(ticket.getCreator()).isNotNull();
        assertThat(ticket.getCreator().getUsername()).isEqualTo("priya");
        assertThat(ticket.getSource()).isEqualTo(Ticket.SOURCE_EMAIL);
    }

    @Test
    void anUnregisteredColleagueAtAKnownDomainGetsTheirCompanysClientButNoCreator() {
        Client acme = client(1L, "Acme");
        when(userRepository.findByEmailEndingWithIgnoreCaseOrderByIdAsc("@customer.com"))
                .thenReturn(List.of(user(7L, "priya", "priya@customer.com", acme)));

        service.ingest(mail("newhire@customer.com", List.of(TECHNICAL), List.of()));

        Ticket ticket = savedTicket();
        assertThat(ticket.getClient()).isEqualTo(acme);
        // The colleague is evidence of the company, not the author — attributing the ticket to
        // them would be a lie the agent then acts on.
        assertThat(ticket.getCreator()).isNull();
    }

    @Test
    void aStrangerAtGmailIsNeverMatchedToWhicheverClientHasAGmailUser() {
        // Domain matching on a consumer provider would hand a stranger another customer's ticket
        // history. gmail.com identifies nobody.
        Client acme = client(1L, "Acme");
        when(userRepository.findByEmailEndingWithIgnoreCaseOrderByIdAsc("@gmail.com"))
                .thenReturn(List.of(user(7L, "priya", "priya@gmail.com", acme)));

        service.ingest(mail("stranger@gmail.com", List.of(TECHNICAL), List.of()));

        assertThat(savedTicket().getClient().getName()).isEqualTo("Email Intake");
        verify(userRepository, never()).findByEmailEndingWithIgnoreCaseOrderByIdAsc("@gmail.com");
    }

    @Test
    void anInternalDomainIsNotUsedForDomainMatching() {
        service.ingest(mail("someone@xammer.in", List.of(TECHNICAL), List.of()));

        assertThat(savedTicket().getClient().getName()).isEqualTo("Email Intake");
        verify(userRepository, never()).findByEmailEndingWithIgnoreCaseOrderByIdAsc("@xammer.in");
    }

    @Test
    void aCompletelyUnknownSenderStillGetsARealTicket() {
        // The explicit requirement: never reject. An unrecognised sender is parked under the
        // intake client for triage, not dropped.
        EmailTicketIngestService.Result result =
                service.ingest(mail("nobody@unknown-co.example", List.of(TECHNICAL), List.of()));

        assertThat(result.createdTicket()).isTrue();
        Ticket ticket = savedTicket();
        assertThat(ticket.getClient().getName()).isEqualTo("Email Intake");
        assertThat(ticket.getCreator()).isNull();
        assertThat(ticket.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void createsTheIntakeClientTheFirstTimeItIsNeeded() {
        when(clientRepository.findByName("Email Intake")).thenReturn(Optional.empty());
        when(clientRepository.save(any())).thenAnswer(i -> {
            Client saved = i.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        service.ingest(mail("nobody@unknown-co.example", List.of(TECHNICAL), List.of()));

        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Email Intake");
    }

    // ------------------------------------------------------------------ threading

    @Test
    void aReplyLandsOnTheTicketItsThreadOpened() {
        Ticket existing = existingTicket(42L, "Technical", "IN_PROGRESS");
        TicketEmailMessage ancestor = new TicketEmailMessage();
        ancestor.setMessageId("original@customer.com");
        ancestor.setTicket(existing);
        when(emailMessageRepository.findByMessageIdInWithTicket(any())).thenReturn(List.of(ancestor));

        ParsedEmail reply = new ParsedEmail("reply@customer.com", "original@customer.com",
                List.of("original@customer.com"), "priya@customer.com", "Priya",
                List.of(TECHNICAL), List.of(), "Re: EC2 restarting", LocalDateTime.now(),
                "Still broken.", false, false, List.of());

        EmailTicketIngestService.Result result = service.ingest(reply);

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_REPLY_APPENDED);
        assertThat(result.ticketId()).isEqualTo(42L);
        assertThat(existing.getReplies()).hasSize(1);
        TicketReply appended = existing.getReplies().get(0);
        assertThat(appended.getAuthorEmail()).isEqualTo("priya@customer.com");
        assertThat(appended.getAuthor()).isNull();
        assertThat(appended.getMessage()).contains("Still broken.");
        assertThat(existing.isHasNewUserReply()).isTrue();
    }

    @Test
    void aReplyToOurAcknowledgementThreadsEvenThoughItCopiesNeitherGroupAddress() {
        // The acknowledgement goes from notifications@ to the sender alone, so their reply to it
        // carries no group address — our own mail never put one there. Requiring a mention on
        // every message would silently drop exactly the replies this feature exists to collect.
        Ticket existing = existingTicket(42L, "Technical", "OPEN");
        TicketEmailMessage ancestor = new TicketEmailMessage();
        ancestor.setMessageId("original@customer.com");
        ancestor.setTicket(existing);
        when(emailMessageRepository.findByMessageIdInWithTicket(any())).thenReturn(List.of(ancestor));

        EmailTicketIngestService.Result result = service.ingest(new ParsedEmail(
                "reply-to-ack@customer.com", "original@customer.com", List.of("original@customer.com"),
                "priya@customer.com", "Priya",
                List.of("notifications@xamops.com"), List.of(),   // no group address anywhere
                "Re: Received — Ticket [ID: 42]", LocalDateTime.now(),
                "Any update on this?", false, false, List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_REPLY_APPENDED);
        assertThat(existing.getReplies()).hasSize(1);
    }

    @Test
    void unrelatedMailWithNoGroupAddressAndNoThreadIsStillIgnored() {
        // The mention rule still gates NEW tickets, or every newsletter reaching the mailbox
        // would open one.
        EmailTicketIngestService.Result result = service.ingest(new ParsedEmail(
                "newsletter@vendor.example", null, List.of(),
                "news@vendor.example", "Vendor",
                List.of("notifications@xamops.com"), List.of(),
                "Our September product update", LocalDateTime.now(),
                "Read about our new features!", false, false, List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_SKIPPED_NO_MATCH);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void mailOnAClosedTicketReopensItRatherThanBeingLost() {
        Ticket closed = existingTicket(42L, "Technical", "CLOSED");
        closed.setClosedAt(LocalDateTime.now());
        TicketEmailMessage ancestor = new TicketEmailMessage();
        ancestor.setMessageId("original@customer.com");
        ancestor.setTicket(closed);
        when(emailMessageRepository.findByMessageIdInWithTicket(any())).thenReturn(List.of(ancestor));

        service.ingest(new ParsedEmail("again@customer.com", "original@customer.com",
                List.of("original@customer.com"), "priya@customer.com", "Priya",
                List.of(TECHNICAL), List.of(), "Re: EC2 restarting", LocalDateTime.now(),
                "It is happening again.", false, false, List.of()));

        assertThat(closed.getStatus()).isEqualTo("OPEN");
        assertThat(closed.getClosedAt()).isNull();
        assertThat(closed.getReplies()).hasSize(1);
    }

    @Test
    void aSubjectTicketNumberDoesNotGraftAStrangerOntoSomeoneElsesTicket() {
        // Quoting "#42" must not be enough to join a thread — that would expose another
        // customer's correspondence to whoever typed the number.
        Ticket someoneElses = existingTicket(42L, "Technical", "OPEN");
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(someoneElses));

        EmailTicketIngestService.Result result = service.ingest(new ParsedEmail(
                "stranger@customer.com", null, List.of(), "stranger@elsewhere.example", "Stranger",
                List.of(TECHNICAL), List.of(), "Question about ticket #42", LocalDateTime.now(),
                "What is going on here?", false, false, List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_TICKET_CREATED);
        assertThat(someoneElses.getReplies()).isEmpty();
    }

    @Test
    void aSubjectTicketNumberThreadsWhenTheSenderIsAlreadyOnTheTicket() {
        // The fallback for clients that strip References: safe once the sender is a known
        // participant.
        Client acme = client(1L, "Acme");
        Ticket theirs = existingTicket(42L, "Technical", "OPEN");
        theirs.setCreator(user(7L, "priya", "priya@customer.com", acme));
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(theirs));

        EmailTicketIngestService.Result result = service.ingest(new ParsedEmail(
                "followup@customer.com", null, List.of(), "priya@customer.com", "Priya",
                List.of(TECHNICAL), List.of(), "Re: [ID: 42] EC2 restarting", LocalDateTime.now(),
                "Any update?", false, false, List.of()));

        assertThat(result.status()).isEqualTo(TicketEmailMessage.STATUS_REPLY_APPENDED);
        assertThat(theirs.getReplies()).hasSize(1);
    }

    // ------------------------------------------------------------------ content

    @Test
    void stripsReplyPrefixesFromTheSubjectOfANewTicket() {
        service.ingest(new ParsedEmail("fwd@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of(TECHNICAL), List.of(),
                "Fwd: Re: Disk full on prod", LocalDateTime.now(), "See below.", false, false, List.of()));

        assertThat(savedTicket().getSubject()).isEqualTo("Disk full on prod");
    }

    @Test
    void aSubjectlessMailStillGetsAUsableTitle() {
        // ticket.subject is NOT NULL, so an empty subject cannot be passed through.
        service.ingest(new ParsedEmail("nosubject@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of(TECHNICAL), List.of(),
                "", LocalDateTime.now(), "body", false, false, List.of()));

        assertThat(savedTicket().getSubject()).contains("priya@customer.com");
    }

    @Test
    void escapesAPlainTextBodySoItCannotInjectMarkup() {
        service.ingest(new ParsedEmail("xss@customer.com", null, List.of(),
                "priya@customer.com", "Priya", List.of(TECHNICAL), List.of(),
                "Odd characters", LocalDateTime.now(),
                "<script>alert(1)</script> and 5 < 6", false, false, List.of()));

        assertThat(savedTicket().getDescription()).doesNotContain("<script>");
    }

    @Test
    void recordsTheMailDetailsAgainstTheTicket() {
        service.ingest(mail("priya@customer.com", List.of("colleague@customer.com"), List.of(TECHNICAL)));

        ArgumentCaptor<TicketEmailMessage> captor = ArgumentCaptor.forClass(TicketEmailMessage.class);
        verify(emailMessageRepository).saveAndFlush(captor.capture());
        TicketEmailMessage record = captor.getValue();

        assertThat(record.getFromAddress()).isEqualTo("priya@customer.com");
        assertThat(record.getCcAddresses()).isEqualTo(TECHNICAL);
        assertThat(record.getToAddresses()).isEqualTo("colleague@customer.com");
        assertThat(record.getMailboxAddress()).isEqualTo(TECHNICAL);
        assertThat(record.getStatus()).isEqualTo(TicketEmailMessage.STATUS_TICKET_CREATED);
        assertThat(record.getMatchMethod()).isEqualTo(TicketEmailMessage.MATCH_UNIDENTIFIED);
        assertThat(record.getTicket()).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private ParsedEmail mail(String from, List<String> to, List<String> cc) {
        return new ParsedEmail("msg-" + from.hashCode() + "@test", null, List.of(),
                from, "Sender", to, cc, "A support request", LocalDateTime.now(),
                "Please help.", false, false, List.of());
    }

    private Ticket savedTicket() {
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private Ticket existingTicket(Long id, String category, String status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setCategory(category);
        ticket.setStatus(status);
        ticket.setSubject("EC2 restarting");
        ticket.setClient(client(1L, "Acme"));
        ticket.setSource(Ticket.SOURCE_EMAIL);
        return ticket;
    }

    private Client client(Long id, String name) {
        Client client = new Client(name);
        client.setId(id);
        return client;
    }

    private User user(Long id, String username, String email, Client client) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole("ROLE_XAMOPS");
        user.setClient(client);
        return user;
    }
}
