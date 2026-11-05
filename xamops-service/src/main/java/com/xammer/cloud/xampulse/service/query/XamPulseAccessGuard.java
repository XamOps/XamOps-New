package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * XamPulse's tenancy gate. Wraps {@link CloudAccountAccessService} and turns its denial into the uniform
 * 404 {@code XP_NOT_FOUND}, so "missing" and "not yours" are indistinguishable to the caller.
 */
@Service("xpAccessGuard")
public class XamPulseAccessGuard {

    private final CloudAccountAccessService access;
    private final CloudAccountRepository accounts;

    public XamPulseAccessGuard(CloudAccountAccessService access, CloudAccountRepository accounts) {
        this.access = access;
        this.accounts = accounts;
    }

    public CloudAccount require(ClientUserDetails user, Long cloudAccountId) {
        try {
            return access.requireAccess(user, cloudAccountId);
        } catch (AccessDeniedException e) {
            throw XamPulseException.notFound();
        }
    }

    /** Authorises every id; any failure is the same 404. Returns ids de-duplicated in request order. */
    public List<Long> requireAll(ClientUserDetails user, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw XamPulseException.invalid("At least one account is required.");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(ids);
        if (unique.size() > 20) {
            throw XamPulseException.invalid("At most 20 accounts per request.");
        }
        List<Long> out = new ArrayList<>();
        for (Long id : unique) {
            out.add(require(user, id).getId());
        }
        return out;
    }

    /** AWS accounts the caller may see, filtered through the same {@code canAccess} rule as every other check. */
    public List<CloudAccount> accessibleAwsAccounts(ClientUserDetails user) {
        if (user == null) {
            return List.of();
        }
        boolean admin = user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(r -> "ROLE_SUPER_ADMIN".equals(r) || "ROLE_BILLOPS_ADMIN".equals(r));
        List<CloudAccount> candidates;
        if (admin) {
            candidates = accounts.findAll();
        } else if (user.getClientId() != null) {
            candidates = accounts.findByClientId(user.getClientId());
        } else {
            candidates = List.of();
        }
        List<CloudAccount> out = new ArrayList<>();
        for (CloudAccount a : candidates) {
            boolean aws = a.getAwsAccountId() != null
                    && (a.getProvider() == null || "AWS".equalsIgnoreCase(a.getProvider()));
            if (aws && access.canAccess(user, a)) {
                out.add(a);
            }
        }
        return out;
    }
}
