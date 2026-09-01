package com.xammer.cloud.service.inventory;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Pushes inventory changes to the two topics the frontend listens on.
 *
 * <p>
 * Both exist because the two pages need different things. The cloud list wants to know
 * <em>that</em> something changed so it can re-read the list itself, and subscribes to a
 * single shared topic. The dashboard's Resource Inventory widget wants the new numbers
 * <em>pushed</em>, and subscribes per account to {@code /topic/dashboard/{id}/inventory}.
 *
 * <p>
 * That second subscription already existed in the frontend and nothing in the backend had
 * ever published to it, so the widget only ever changed on a full page load — while the cloud
 * list beside it updated in seconds. Centralising both pushes here is what stops the next
 * publisher from wiring up one and forgetting the other, which is precisely how the two
 * drifted apart.
 */
@Service
public class InventoryChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(InventoryChangeNotifier.class);

    /** Shared "something changed" topic. One subscription serves every account. */
    public static final String CHANGE_TOPIC = "/topic/inventory-refresh";

    private final SimpMessagingTemplate messagingTemplate;
    private final InventoryProjectionService projection;

    public InventoryChangeNotifier(SimpMessagingTemplate messagingTemplate,
            InventoryProjectionService projection) {
        this.messagingTemplate = messagingTemplate;
        this.projection = projection;
    }

    /**
     * Announces a change and pushes the resulting counts.
     *
     * @param account the tenant connection whose inventory moved
     * @param added   resources that appeared
     * @param removed resources that were tombstoned
     * @param total   resources observed by this pass, or -1 when not applicable
     * @param source  "poll" or "event", surfaced so the UI can distinguish the two
     */
    public void inventoryChanged(CloudAccount account, int added, int removed, int total,
            String source) {
        String awsAccountId = account.getAwsAccountId();
        try {
            messagingTemplate.convertAndSend(CHANGE_TOPIC, (Object) Map.of(
                    "status", "inventory-changed",
                    "accountId", awsAccountId,
                    "added", added,
                    "removed", removed,
                    "total", total,
                    "source", source));
        } catch (Exception e) {
            log.warn("[Inventory] Could not push change for {}: {}", awsAccountId, e.getMessage());
        }
        pushDashboardInventory(account);
    }

    /**
     * Sends the current counts to the dashboard widget for one account.
     *
     * <p>
     * Separate from the announcement above and failure-isolated from it: a dashboard that
     * misses one push corrects itself on the next change or page load, so this must never be
     * allowed to stop the cloud list being told that something moved.
     */
    public void pushDashboardInventory(CloudAccount account) {
        if (account.getId() == null || account.getAwsAccountId() == null) {
            return;
        }
        try {
            DashboardData.ResourceInventory inventory = projection.resourceInventory(account.getId());
            messagingTemplate.convertAndSend(
                    "/topic/dashboard/" + account.getAwsAccountId() + "/inventory", inventory);
            log.debug("[Inventory] Pushed dashboard inventory for {}", account.getAwsAccountId());
        } catch (Exception e) {
            log.warn("[Inventory] Could not push dashboard inventory for {}: {}",
                    account.getAwsAccountId(), e.getMessage());
        }
    }
}
