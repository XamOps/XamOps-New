package com.xammer.cloud.controller;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PasswordResetControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private RedisCacheService redisCacheService;
    @Mock private PasswordEncoder passwordEncoder;

    private PasswordResetController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new PasswordResetController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "emailService", emailService);
        ReflectionTestUtils.setField(controller, "redisCacheService", redisCacheService);
        ReflectionTestUtils.setField(controller, "passwordEncoder", passwordEncoder);
    }

    @Test
    void sendOtp_exception_returnsSanitizedError() {
        when(userRepository.findByEmail(anyString())).thenThrow(new RuntimeException("Sensitive DB Error Details"));

        ResponseEntity<?> response = controller.sendOtp("test@example.com");

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody().toString()).isEqualTo("Error: An error occurred while processing your request.");
        assertThat(response.getBody().toString()).doesNotContain("Sensitive DB Error Details");
    }

    @Test
    void resetPassword_exception_returnsSanitizedError() {
        when(redisCacheService.getOtp(anyString())).thenReturn("123456");
        when(userRepository.findByEmail(anyString())).thenThrow(new RuntimeException("Sensitive Write Error"));

        ResponseEntity<?> response = controller.resetPassword("test@example.com", "123456", "newPass");

        assertThat(response.getStatusCodeValue()).isEqualTo(400);
        assertThat(response.getBody().toString()).isEqualTo("Failed to reset password. Please try again.");
        assertThat(response.getBody().toString()).doesNotContain("Sensitive Write Error");
    }
}
