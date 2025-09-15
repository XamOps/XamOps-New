package com.xammer.cloud.config;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;

    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests((requests) -> requests
                        .antMatchers("/login", "/*.html", "/gcp_*.html", "/css/**", "/js/**", "/images/**", "/icons/**", "/webjars/**").permitAll()
                        .antMatchers("/ws/**").permitAll()
                                .antMatchers("/api/user/profile").authenticated()

                        // ALL authenticated users can manage accounts
                        .antMatchers("/api/xamops/account-manager/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_XAMOPS", "ROLE_BILLOPS")
                        // XAMOPS, BILLOPS, and ADMIN specific endpoints
                        .antMatchers("/api/xamops/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN", "ROLE_BILLOPS")
                        .antMatchers("/api/cloudlist/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .antMatchers("/api/cloudmap/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .antMatchers("/api/costing/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .antMatchers("/api/finops/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .antMatchers("/api/metrics/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .antMatchers("/api/security/**").hasAnyAuthority("ROLE_XAMOPS", "ROLE_ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin((form) -> form
                        .loginPage("/login")
                        .successHandler(authenticationSuccessHandler)
                        .permitAll()
                )
                .logout((logout) -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(user -> {
                    Long clientId = Optional.ofNullable(user.getClient())
                            .map(Client::getId)
                            .orElse(null);

                    GrantedAuthority authority = new SimpleGrantedAuthority(user.getRole());
                    List<GrantedAuthority> authorities = Collections.singletonList(authority);

                    return new ClientUserDetails(
                            user.getUsername(),
                            user.getPassword(),
                            authorities,
                            clientId
                    );
                })
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public HttpFirewall allowSemicolonHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        return firewall;
    }
}