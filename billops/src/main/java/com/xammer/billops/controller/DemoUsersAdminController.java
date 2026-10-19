package com.xammer.billops.controller;

import com.xammer.billops.dto.DemoUserDto;
import com.xammer.billops.dto.DemoUserLoginAgg;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.UserActivityEventRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.UserActivityEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin "Demo Users" view: lists every user of the {@code is_demo=true} client together with their
 * login history (auth provider, first-seen, last-login, login count, optional last IP) read from
 * {@code user_activity_event} LOGIN rows. Read-only; it adds no column to any mapping (NFR2) and
 * uses only existing billops repositories plus the new query methods and DTO.
 *
 * <p>Guarded by the same {@code @PreAuthorize} as the peer BillOps admin endpoints
 * (e.g. {@code SupportAdminController}); no {@code @CrossOrigin} is added (same-origin via nginx).
 */
@RestController
@RequestMapping("/api/billops/admin")
public class DemoUsersAdminController {

    private final AppUserRepository appUserRepository;
    private final UserActivityEventRepository eventRepository;

    public DemoUsersAdminController(AppUserRepository appUserRepository,
                                    UserActivityEventRepository eventRepository) {
        this.appUserRepository = appUserRepository;
        this.eventRepository = eventRepository;
    }

    @GetMapping("/demo-users")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public List<DemoUserDto> demoUsers() {
        List<AppUser> users = appUserRepository.findByClient_Demo(true);
        if (users.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = users.stream().map(AppUser::getId).collect(Collectors.toList());
        Map<Long, DemoUserLoginAgg> aggByUserId = eventRepository.aggregateLoginsByUserIds(userIds).stream()
                .collect(Collectors.toMap(DemoUserLoginAgg::getUserId, Function.identity()));

        List<DemoUserDto> result = new ArrayList<>(users.size());
        for (AppUser user : users) {
            DemoUserLoginAgg agg = aggByUserId.get(user.getId());
            if (agg == null) {
                result.add(new DemoUserDto(user.getEmail(), user.getUsername(),
                        null, null, null, 0L, null));
                continue;
            }
            String lastIp = eventRepository
                    .findFirstByUserIdAndActionOrderByCreatedAtDesc(user.getId(), UserActivityEvent.ACTION_LOGIN)
                    .map(UserActivityEvent::getIpAddress)
                    .orElse(null);
            result.add(new DemoUserDto(
                    user.getEmail(),
                    user.getUsername(),
                    agg.getAuthProvider(),
                    agg.getFirstSeen(),
                    agg.getLastLogin(),
                    agg.getLoginCount(),
                    lastIp));
        }
        return result;
    }
}
