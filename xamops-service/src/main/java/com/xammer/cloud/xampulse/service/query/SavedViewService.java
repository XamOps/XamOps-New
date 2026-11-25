package com.xammer.cloud.xampulse.service.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpSavedView;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpSavedViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved views (logs, metrics, hosts). Private by default; "shared" makes a view visible to everyone with the account. */
@Service("xpSavedViewService")
public class SavedViewService {

    private static final Set<String> KINDS = Set.of("LOGS", "METRICS", "HOSTS");
    private static final int MAX_STATE_BYTES = 20_000;

    private final XpSavedViewRepository repo;
    private final XamPulseAccessGuard guard;
    private final ObjectMapper mapper;

    public SavedViewService(XpSavedViewRepository repo, XamPulseAccessGuard guard, ObjectMapper objectMapper) {
        this.repo = repo;
        this.guard = guard;
        this.mapper = objectMapper;
    }

    public List<com.xammer.cloud.xampulse.dto.XpSavedView> list(ClientUserDetails user, Long accountId) {
        guard.require(user, accountId);
        return repo.findVisible(accountId, user.getId()).stream().map(this::toDto).toList();
    }

    public com.xammer.cloud.xampulse.dto.XpSavedView create(ClientUserDetails user, Long accountId, com.xammer.cloud.xampulse.dto.XpSavedView in) {
        guard.require(user, accountId);
        if (!KINDS.contains(in.kind())) {
            throw XamPulseException.invalid("Unknown view type.");
        }
        XpSavedView e = new XpSavedView();
        e.setCloudAccountId(accountId);
        e.setOwnerUserId(user.getId());
        e.setName(in.name().trim());
        e.setKind(in.kind());
        e.setShared(in.shared());
        e.setCreatedAt(Instant.now());
        try {
            String json = mapper.writeValueAsString(in.state());
            if (json.length() > MAX_STATE_BYTES) {
                throw XamPulseException.invalid("The view is too large to save.");
            }
            e.setStateJson(json);
        } catch (JsonProcessingException ex) {
            throw XamPulseException.invalid("The view could not be saved.");
        }
        return toDto(repo.save(e));
    }

    @Transactional
    public void delete(ClientUserDetails user, Long accountId, Long id) {
        guard.require(user, accountId);
        if (repo.findByIdAndCloudAccountIdAndOwnerUserId(id, accountId, user.getId()).isEmpty()) {
            throw XamPulseException.notFound();
        }
        repo.deleteByIdAndCloudAccountIdAndOwnerUserId(id, accountId, user.getId());
    }

    private com.xammer.cloud.xampulse.dto.XpSavedView toDto(XpSavedView v) {
        try {
            Map<String, Object> state = mapper.readValue(v.getStateJson(), new TypeReference<Map<String, Object>>() {});
            return new com.xammer.cloud.xampulse.dto.XpSavedView(v.getId(), v.getName(), v.getKind(), v.isShared(), state);
        } catch (JsonProcessingException e) {
            return new com.xammer.cloud.xampulse.dto.XpSavedView(v.getId(), v.getName(), v.getKind(), v.isShared(), Map.of());
        }
    }
}
