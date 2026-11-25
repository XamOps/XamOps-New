package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.TicketEmailMessageRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.security.ClientUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService#closeTicket}: a customer can close a ticket they can see, and no other; an
 * admin can close any ticket. Repositories are mocked, but the ticket scoping that decides what a
 * customer can see is the real code that {@code getTicketById} also uses.
 */
class TicketServiceCloseTicketTest {

    private static final long TICKET_ID = 9L;
    private static final long CLIENT_ID = 3L;
    private static final long CUSTOMER_ID = 7L;
    private static final long ADMIN_ID = 1L;
    private static final String CUSTOMER_AWS_ACCOUNT = "111111111111";
    private static final String OTHER_AWS_ACCOUNT = "222222222222";
    private static final String INTERNAL_NOTE = "Internal: waiting on finance before refunding";

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private TicketReplyRepository ticketReplyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private RedisCacheService redisCache;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @Mock
    private CloudAccountRepository cloudAccountRepository;
    @Mock
    private TicketEmailMessageRepository ticketEmailMessageRepository;
    @Mock
    private AdminAccessScope adminAccessScope;

    @InjectMocks
    private TicketService ticketService;

    private AutoCloseable mocks;
    private User customer;
    private User supportAgent;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        Client client = new Client();
        client.setId(CLIENT_ID);

        customer = user(CUSTOMER_ID, "jane", "jane@customer.example", "ROLE_BILLOPS");
        supportAgent = user(ADMIN_ID, "alice.support", "alice@xamops.example", "ROLE_BILLOPS_ADMIN");

        // A primary user of the client (createdBy == null) with no per-account grants sees every
        // account the client owns, which here is one AWS account.
        AppUser customerLogin = new AppUser();
        customerLogin.setId(CUSTOMER_ID);
        customerLogin.setUsername("jane");
        customerLogin.setClient(client);
        when(appUserRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customerLogin));

        CloudAccount aws = new CloudAccount();
        aws.setProvider("AWS");
        aws.setAwsAccountId(CUSTOMER_AWS_ACCOUNT);
        when(cloudAccountRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(aws));

        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        mocks.close();
    }

    @Test
    void customerClosesTheirOwnTicketAndGetsTheCustomerView() {
        Ticket ticket = openTicketOn(CUSTOMER_AWS_ACCOUNT, customer);
        signIn(CUSTOMER_ID, "ROLE_BILLOPS");

        TicketDto result = ticketService.closeTicket(TICKET_ID, false);

        assertThat(ticket.getStatus()).isEqualTo("CLOSED");
        assertThat(ticket.getClosedAt()).isNotNull();
        verify(ticketRepository).save(ticket);
        assertThat(result.getStatus()).isEqualTo("CLOSED");

        // The page renders whatever this returns, so internal notes and agent names stay hidden.
        assertThat(result.getReplies()).extracting(TicketReplyDto::getMessage).doesNotContain(INTERNAL_NOTE);
        assertThat(result.getReplies()).noneMatch(TicketReplyDto::isInternal);
        assertThat(result.getReplies()).extracting(TicketReplyDto::getAuthorUsername)
                .containsExactly("jane", "Support Team");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jane@customer.example"), anyString(), html.capture());
        assertThat(html.getValue())
                .contains("Your support ticket has been closed.")
                .doesNotContain("closed by our team");
    }

    @Test
    void customerCannotCloseAnotherClientsTicket() {
        User otherClientsUser = user(99L, "bob", "bob@other.example", "ROLE_BILLOPS");
        Ticket ticket = openTicketOn(OTHER_AWS_ACCOUNT, otherClientsUser);
        signIn(CUSTOMER_ID, "ROLE_BILLOPS");

        // Same message as a ticket that doesn't exist, so the controller answers 404 either way.
        assertThatThrownBy(() -> ticketService.closeTicket(TICKET_ID, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Ticket not found");

        assertThat(ticket.getStatus()).isEqualTo("OPEN");
        assertThat(ticket.getClosedAt()).isNull();
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(emailService, redisCache);
    }

    @Test
    void adminClosesAnyTicketAndGetsTheAdminView() {
        User otherClientsUser = user(99L, "bob", "bob@other.example", "ROLE_BILLOPS");
        Ticket ticket = openTicketOn(OTHER_AWS_ACCOUNT, otherClientsUser);
        signIn(ADMIN_ID, "ROLE_BILLOPS_ADMIN");

        TicketDto result = ticketService.closeTicket(TICKET_ID, true);

        assertThat(ticket.getStatus()).isEqualTo("CLOSED");
        assertThat(result.getReplies()).extracting(TicketReplyDto::getMessage).contains(INTERNAL_NOTE);
        assertThat(result.getReplies()).extracting(TicketReplyDto::getAuthorUsername)
                .containsExactly("bob", "alice.support", "alice.support");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("bob@other.example"), anyString(), html.capture());
        assertThat(html.getValue()).contains("Your support ticket has been resolved and closed by our team.");
    }

    /** An open ticket on the given account: the opening message, a support reply and an internal note. */
    private Ticket openTicketOn(String awsAccountId, User creator) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setSubject("Invoice total looks wrong");
        ticket.setStatus("OPEN");
        ticket.setCategory("Account and Billing");
        ticket.setAccountId(awsAccountId);
        ticket.setCreator(creator);

        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 10, 0);
        List<TicketReply> replies = new ArrayList<>();
        replies.add(reply(ticket, 1L, creator, "The total doesn't match the AWS console", false, start));
        replies.add(reply(ticket, 2L, supportAgent, "Looking into it now", false, start.plusMinutes(5)));
        replies.add(reply(ticket, 3L, supportAgent, INTERNAL_NOTE, true, start.plusMinutes(10)));
        ticket.setReplies(replies);

        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private static TicketReply reply(Ticket ticket, long id, User author, String message, boolean internal,
                                     LocalDateTime createdAt) {
        TicketReply reply = new TicketReply();
        reply.setId(id);
        reply.setTicket(ticket);
        reply.setAuthor(author);
        reply.setMessage(message);
        reply.setInternal(internal);
        reply.setCreatedAt(createdAt);
        return reply;
    }

    private static User user(long id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    private static void signIn(long userId, String role) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
        ClientUserDetails principal = new ClientUserDetails("user" + userId, null, authorities, CLIENT_ID, userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }
}
