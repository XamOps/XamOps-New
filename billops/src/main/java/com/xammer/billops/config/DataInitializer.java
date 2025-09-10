package com.xammer.billops.config;

import com.xammer.billops.domain.Customer;
import com.xammer.billops.domain.User;
import com.xammer.billops.repository.CustomerRepository;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        // Create a default admin user and customer if they don't exist
        if (userRepository.findByUsername("admin").isEmpty()) {
            User adminUser = new User();
            adminUser.setUsername("admin");
            adminUser.setPassword(passwordEncoder.encode("password"));
            adminUser.setRole("ROLE_ADMIN");
            userRepository.save(adminUser);

            Customer adminCustomer = new Customer();
            adminCustomer.setUser(adminUser);
            adminCustomer.setCompanyName("Admin Company");
            customerRepository.save(adminCustomer);
            System.out.println("Created default admin user and customer.");
        }

        // --- NEW HARDCODED USER FOR TESTING ---
        // Create a default test user and customer if they don't exist
        if (userRepository.findByUsername("testuser").isEmpty()) {
            User testUser = new User();
            testUser.setUsername("testuser");
            testUser.setPassword(passwordEncoder.encode("password"));
            testUser.setRole("ROLE_USER");
            userRepository.save(testUser);

            Customer testCustomer = new Customer();
            testCustomer.setUser(testUser);
            testCustomer.setCompanyName("Test Company");
            customerRepository.save(testCustomer);
            System.out.println("Created default test user and customer.");
        }
    }
}