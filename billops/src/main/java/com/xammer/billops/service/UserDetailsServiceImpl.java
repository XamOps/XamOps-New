package com.xammer.billops.service;

import java.util.Collections;
import java.util.List;
import java.util.Optional; // ✅ ADD

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// ✅ CHANGE THESE IMPORTS
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.domain.Client;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.cloud.security.ClientUserDetails; // ✅ ADD THIS IMPORT

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private AppUserRepository userRepository; // ✅ CHANGED TO AppUserRepository

    @Override
    @Cacheable(value = "users", key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username: " + username));
        
        // --- THIS IS THE FIX ---
        // We create the wrapper, just like in xamops-service
        
        Long clientId = Optional.ofNullable(user.getClient())
                            .map(Client::getId)
                            .orElse(null);

        GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole());
        List<GrantedAuthority> authorities = Collections.singletonList(authority);

        // Return the wrapper, passing in all the user's details
        return new ClientUserDetails(
                user.getUsername(),
                user.getPassword(),
                authorities,
                clientId,
                user.getId() // <-- This is the user ID the frontend needs
        );
        // --- END: MODIFIED ---
    }
}