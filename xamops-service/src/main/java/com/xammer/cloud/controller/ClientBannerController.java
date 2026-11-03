package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AdminBannerDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AdminBannerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Announcement banners for the signed-in XamOps user's client.
 *
 * <p>
 * The client comes from the principal, never from a parameter, as in
 * {@link SecurityNotificationController}. During an admin "Login as" the impersonation filters
 * put the target user's principal here, so the admin sees what that client sees.
 */
@RestController
@RequestMapping("/api/xamops/banners")
public class ClientBannerController {

    private final AdminBannerService service;

    public ClientBannerController(AdminBannerService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminBannerDto.ClientView> mine(@AuthenticationPrincipal ClientUserDetails user) {
        return service.visibleTo(user == null ? null : user.getClientId());
    }
}
