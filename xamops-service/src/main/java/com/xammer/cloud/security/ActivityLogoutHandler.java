package com.xammer.cloud.security;

import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.service.UserActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Records a {@link UserActivityEvent#ACTION_LOGOUT} event on sign-out.
 *
 * <p>Registered through {@code addLogoutHandler} rather than inside
 * {@link CustomLogoutSuccessHandler} for a timing reason. Spring Security runs the handlers
 * registered on the logout configurer <em>before</em> its own
 * {@code SecurityContextLogoutHandler}, which is what performs
 * {@code invalidateHttpSession(true)}. A success handler therefore runs after the session is
 * already gone, leaving no session id to hash — and without that id the logout cannot be
 * paired with its login, which is the only way a session duration can be derived.
 *
 * <p>Note what this does <em>not</em> capture: closing a tab produces no logout request at
 * all. Session durations are therefore only available for sessions that ended with a
 * deliberate sign-out, and the API reports the sample size alongside the average so the
 * figure is not mistaken for covering every session.
 */
@Component
public class ActivityLogoutHandler implements LogoutHandler {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogoutHandler.class);

    private final UserActivityService userActivityService;

    public ActivityLogoutHandler(UserActivityService userActivityService) {
        this.userActivityService = userActivityService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {
        try {
            if (authentication == null
                    || !(authentication.getPrincipal() instanceof ClientUserDetails user)) {
                // Anonymous or already-expired session hitting /logout. Nothing to attribute.
                return;
            }
            userActivityService.recordLogout(user.getId(), user.getClientId(), request);
        } catch (Exception e) {
            // Never let an audit write interfere with signing out.
            log.warn("Failed to record logout event: {}", e.getMessage());
        }
    }
}
