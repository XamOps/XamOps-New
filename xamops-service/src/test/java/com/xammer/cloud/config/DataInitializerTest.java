package com.xammer.cloud.config;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(dataInitializer, "defaultSuperAdminPassword", "testSuperPass");
        ReflectionTestUtils.setField(dataInitializer, "defaultDemoPassword", "testDemoPass");
    }

    @Test
    void testCreateSuperAdminIfMissing_WhenMissing() {
        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("testSuperPass")).thenReturn("encodedSuperPass");

        Client client = new Client("Test Client");

        ReflectionTestUtils.invokeMethod(dataInitializer, "createSuperAdminIfMissing", client);

        verify(userRepository).save(any(User.class));
        verify(passwordEncoder).encode("testSuperPass");
    }

    @Test
    void testRun_SeedsDemoUsersWithCorrectPassword() {
        Client client = new Client("Default Client");
        when(clientRepository.count()).thenReturn(1L);
        when(clientRepository.findAll()).thenReturn(Collections.singletonList(client));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(new User()));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("testDemoPass")).thenReturn("encodedDemoPass");

        try {
            dataInitializer.run();
        } catch (Exception e) {
            // ignore
        }

        verify(passwordEncoder, atLeastOnce()).encode("testDemoPass");
    }
}
