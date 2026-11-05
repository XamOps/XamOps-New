package com.xammer.cloud.xampulse.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpSavedView;
import com.xammer.cloud.xampulse.service.platform.IdempotencyService;
import com.xammer.cloud.xampulse.service.query.SavedViewService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController("xpViewsController")
@RequestMapping("/api/xamops/xampulse/views")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseViewsController {

    private final SavedViewService views;
    private final IdempotencyService idempotency;

    public XamPulseViewsController(SavedViewService views) {
        this(views, null);
    }

    @Autowired
    public XamPulseViewsController(SavedViewService views, IdempotencyService idempotency) {
        this.views = views;
        this.idempotency = idempotency;
    }

    @GetMapping
    @PreAuthorize(XamPulseRoles.VIEWER)
    public List<XpSavedView> list(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId) {
        return views.list(user, accountId);
    }

    @PostMapping
    @PreAuthorize(XamPulseRoles.VIEWER)
    public XpSavedView create(@AuthenticationPrincipal ClientUserDetails user, @RequestParam Long accountId,
                              @Valid @RequestBody XpSavedView body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotency == null) {
            return views.create(user, accountId, body);
        }
        return idempotency.execute(user.getId(), "POST /views?accountId=" + accountId, idempotencyKey, body, XpSavedView.class,
                () -> views.create(user, accountId, body));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(XamPulseRoles.VIEWER)
    public ResponseEntity<Void> delete(@AuthenticationPrincipal ClientUserDetails user, @PathVariable Long id, @RequestParam Long accountId) {
        views.delete(user, accountId, id);
        return ResponseEntity.noContent().build();
    }
}
