package com.xammer.billops.controller;

import com.xammer.billops.dto.AdminDashboardDto;
import com.xammer.billops.dto.UserDashboardDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.service.AdminDashboardService;
import com.xammer.billops.service.UserDashboardService;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billops/dashboard")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com",
        "https://live.xamops.com", "https://prep.xamops.com" }, methods = { RequestMethod.GET, RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.DELETE, RequestMethod.OPTIONS }, allowCredentials = "true")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final AdminDashboardService adminDashboardService;
    private final UserDashboardService userDashboardService;
    private final AppUserRepository userRepository;

    public DashboardController(AdminDashboardService adminDashboardService,
            UserDashboardService userDashboardService,
            AppUserRepository userRepository) {
        this.adminDashboardService = adminDashboardService;
        this.userDashboardService = userDashboardService;
        this.userRepository = userRepository;
    }

    /**
     * Admin Dashboard endpoint - shows aggregated data across all clients and
     * accounts
     * Only accessible by users with ROLE_BILLOPS_ADMIN
     */
    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<AdminDashboardDto> getAdminDashboard(
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh,
            Authentication authentication) {

        try {
            logger.info("Admin dashboard requested by user: {}", authentication.getName());
            AdminDashboardDto dashboard = adminDashboardService.getAdminDashboardSummary(forceRefresh);
            return ResponseEntity.ok(dashboard);
        } catch (Exception e) {
            logger.error("Error fetching admin dashboard: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
<<<<<<< HEAD
     * User Dashboard endpoint - shows data specific to the logged-in user's accounts
     * Accessible by users with ROLE_BILLOPS, ROLE_BILLOPS_ADMIN, or ROLE_XAMOPS
=======
     * User Dashboard endpoint - shows data specific to the logged-in user's
     * accounts
     * Accessible by users with ROLE_BILLOPS or ROLE_BILLOPS_ADMIN
>>>>>>> 32fb69be (preprod update)
     */
    @GetMapping("/user")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS', 'ROLE_BILLOPS_ADMIN', 'ROLE_XAMOPS')")
    public ResponseEntity<UserDashboardDto> getUserDashboard(
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh,
            Authentication authentication) {

        try {
            String username = authentication.getName();
            logger.info("User dashboard requested by user: {}", username);

            AppUser user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

            UserDashboardDto dashboard = userDashboardService.getUserDashboardSummary(user, forceRefresh);
            return ResponseEntity.ok(dashboard);
        } catch (UsernameNotFoundException e) {
            logger.error("User not found: ", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            logger.error("Error fetching user dashboard: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
