package com.xammer.billops.service;

import com.xammer.billops.domain.Customer;
import com.xammer.billops.domain.User;
import com.xammer.billops.repository.CustomerRepository;
import com.xammer.billops.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Import Transactional

import java.util.List;

@Service
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public List<Customer> findAllCustomers() {
        return customerRepository.findAll();
    }

    public void createCustomerWithUser(Customer customer) {
        // Create a new user for this customer
        User newUser = new User();
        // Generate a username from company name (e.g., "acmecorp")
        String username = customer.getCompanyName().toLowerCase().replaceAll("\\s+", "");
        newUser.setUsername(username);
        // For simplicity, we can set a default password or generate a random one
        newUser.setPassword(passwordEncoder.encode("Password123!")); // Default password
        newUser.setRole("ROLE_USER");
        userRepository.save(newUser);

        // Link the new user to the customer and save
        customer.setUser(newUser);
        customerRepository.save(customer);
    }
    public Customer findByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        return customerRepository.findByUser(user)
                .orElseThrow(() -> new RuntimeException("Customer not found for user: " + username));
    }

public void updateCustomerArn(String username, String awsRoleArn) {
    Customer customer = findByUsername(username);
    customer.setAwsRoleArn(awsRoleArn);
    customerRepository.save(customer);
}

    @Transactional(readOnly = true) // Use a read-only transaction for fetching data
    public Customer findByUsernameWithCloudAccounts(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));
        return customerRepository.findByUserWithCloudAccounts(user)
                .orElseThrow(() -> new RuntimeException("Customer not found for user: " + username));
    }
}