package com.xammer.cloud.config;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(ClientRepository clientRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.clientRepository = clientRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        if (clientRepository.count() == 0) {
            logger.info("Initializing default client and users...");

            Client defaultClient = new Client("Default Client");
            clientRepository.save(defaultClient);

            // Admin User (can see everything)
            User adminUser = new User("admin", passwordEncoder.encode("password"), defaultClient);
            adminUser.setRole("ROLE_ADMIN");
            userRepository.save(adminUser);

            // XamOps User
            User xamopsUser = new User("xamopsuser", passwordEncoder.encode("password"), defaultClient);
            xamopsUser.setRole("ROLE_XAMOPS");
            userRepository.save(xamopsUser);

            // BillOps User
            User billopsUser = new User("billopsuser", passwordEncoder.encode("password"), defaultClient);
            billopsUser.setRole("ROLE_BILLOPS");
            userRepository.save(billopsUser);

            logger.info("Default users created successfully.");
        } else {
            logger.info("Database already contains client data. Skipping initialization.");
        }
    }
}