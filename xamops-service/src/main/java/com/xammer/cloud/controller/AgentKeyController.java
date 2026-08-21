package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AgentApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manage the agent API key for one cloud account.
 *
 * Agents used to share a single key that was published in the install scripts and the frontend
 * bundle, so nothing tied a request to an account. Scoped keys fix that, but only if there is a
 * way to obtain and replace one — without this, the only path to a scoped key was the DbOps
 * drawer, and rotating a leaked key meant editing the database.
 *
 * Lives under {@code /api/xamops/**} rather than {@code /api/v1/agent/**} on purpose: the agent
 * paths are {@code permitAll} so agents can reach them, whereas issuing a credential must require
 * a signed-in operator. This path falls through to {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api/xamops/agent-keys")
public class AgentKeyController {

    private static final Logger logger = LoggerFactory.getLogger(AgentKeyController.class);

    private final AgentApiKeyService agentApiKeyService;
    private final CloudAccountRepository cloudAccountRepository;

    public AgentKeyController(AgentApiKeyService agentApiKeyService,
                              CloudAccountRepository cloudAccountRepository) {
        this.agentApiKeyService = agentApiKeyService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * GET /api/xamops/agent-keys?accountId=111122223333
     * Returns the account's agent key, creating one on first call.
     */
    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal ClientUserDetails userDetails,
                                 @RequestParam String accountId) {
        CloudAccount account = resolveOwned(userDetails, accountId);
        if (account == null) return notFound();
        return ResponseEntity.ok(describe(accountId, agentApiKeyService.getOrCreate(account.getId())));
    }

    /**
     * POST /api/xamops/agent-keys/rotate?accountId=111122223333
     *
     * Issues a replacement and invalidates the previous key immediately. Agents still holding the
     * old one stop authenticating on their next request — that is the intent, and it is why this
     * is a separate call from GET rather than something a page refresh could trigger.
     */
    @PostMapping("/rotate")
    public ResponseEntity<?> rotate(@AuthenticationPrincipal ClientUserDetails userDetails,
                                    @RequestParam String accountId) {
        CloudAccount account = resolveOwned(userDetails, accountId);
        if (account == null) return notFound();
        logger.info("Agent key rotation requested for account {} by client {}",
                accountId, userDetails.getClientId());
        Map<String, Object> body = describe(accountId, agentApiKeyService.rotate(account.getId()));
        body.put("warning", "Agents using the previous key will stop reporting until they are "
                + "reconfigured with this one.");
        return ResponseEntity.ok(body);
    }

    /**
     * DELETE /api/xamops/agent-keys?accountId=111122223333
     * Removes the key. This account's VM agents can no longer authenticate.
     */
    @DeleteMapping
    public ResponseEntity<?> revoke(@AuthenticationPrincipal ClientUserDetails userDetails,
                                    @RequestParam String accountId) {
        CloudAccount account = resolveOwned(userDetails, accountId);
        if (account == null) return notFound();
        agentApiKeyService.revoke(account.getId());
        logger.info("Agent key revoked for account {} by client {}", accountId, userDetails.getClientId());
        return ResponseEntity.ok(Map.of("revoked", true, "accountId", accountId));
    }

    /**
     * Resolves the account only if it belongs to the caller's client, so an operator cannot read
     * or rotate another tenant's agent credential.
     */
    private CloudAccount resolveOwned(ClientUserDetails userDetails, String accountId) {
        if (userDetails == null || accountId == null || accountId.isBlank()) return null;
        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId).orElse(null);
        if (account == null || account.getClient() == null
                || !account.getClient().getId().equals(userDetails.getClientId())) {
            return null;
        }
        return account;
    }

    private Map<String, Object> describe(String accountId, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("apiKey", key);
        body.put("scope", "account");
        return body;
    }

    /** Identical response for missing and not-yours, so account ids cannot be probed. */
    private ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Cloud account not found"));
    }
}
