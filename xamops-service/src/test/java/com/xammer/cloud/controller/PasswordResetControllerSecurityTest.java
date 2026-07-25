package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

class PasswordResetControllerSecurityTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private RedisCacheService redisCacheService;
    @Mock private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        PasswordResetController controller = new PasswordResetController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "emailService", emailService);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void sendOtp_userExists_returnsGenericSuccessMessage() throws Exception {
        User user = new User();
        user.setEmail("existing@example.com");
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .param("email", "existing@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("If that email address is registered, an OTP has been sent."));

        verify(redisCacheService).saveOtp(eq("existing@example.com"), anyString());
        verify(emailService).sendHtmlEmail(eq("existing@example.com"), anyString(), anyString());
    }

    @Test
    void sendOtp_userDoesNotExist_returnsSameGenericSuccessMessage_mitigatesEnumeration() throws Exception {
        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .param("email", "nonexistent@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().string("If that email address is registered, an OTP has been sent."));

        verifyNoInteractions(redisCacheService);
        verifyNoInteractions(emailService);
    }

    @Test
    void sendOtp_exceptionOccurs_masksErrorDetails() throws Exception {
        when(userRepository.findByEmail("error@example.com")).thenThrow(new RuntimeException("Database error with sensitive info"));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .param("email", "error@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Failed to process request."));
    }

    @Test
    void resetPassword_exceptionOccurs_masksErrorDetails() throws Exception {
        when(redisCacheService.getOtp("user@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("user@example.com")).thenThrow(new RuntimeException("SQL injection or DB details"));

        mockMvc.perform(post("/api/auth/reset-password")
                        .param("email", "user@example.com")
                        .param("otp", "123456")
                        .param("newPassword", "newSecuredPass123!"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Failed to reset password."));
    }
}
