package com.xammer.cloud.service;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.SupportMessageRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SupportChatServiceTest {

    private SupportMessageRepository messageRepository;
    private UserRepository userRepository;
    private SupportChatService service;
    private User user;

    @BeforeEach
    void setUp() {
        messageRepository = mock(SupportMessageRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SupportChatService(messageRepository, userRepository);

        user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setClient(null);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("x").authorities("ROLE_XAMOPS").build();
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        when(messageRepository.save(any(SupportMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sendUserMessage_stillDefaultsToUnreadStatus() {
        SupportMessage result = service.sendUserMessage("what does disk rightsizing do?", null);

        assertThat(result.getStatus()).isEqualTo("UNREAD");
        assertThat(result.isUserMessage()).isTrue();
        assertThat(result.getContent()).isEqualTo("what does disk rightsizing do?");
    }

    @Test
    void recordAutoResolvedQuestion_setsStatusToRead() {
        SupportMessage result = service.recordAutoResolvedQuestion("what does disk rightsizing do?", null);

        assertThat(result.getStatus()).isEqualTo("READ");
        assertThat(result.isUserMessage()).isTrue();
        assertThat(result.getContent()).isEqualTo("what does disk rightsizing do?");
    }

    @Test
    void sendBotAnswer_createsDeliveredNonUserMessageForCurrentUser() {
        SupportMessage result = service.sendBotAnswer("Disk Rightsizing analyzes your volumes.");

        assertThat(result.getStatus()).isEqualTo("DELIVERED");
        assertThat(result.isUserMessage()).isFalse();
        assertThat(result.getContent()).isEqualTo("Disk Rightsizing analyzes your volumes.");
        assertThat(result.getSender()).isEqualTo(user);
    }
}
