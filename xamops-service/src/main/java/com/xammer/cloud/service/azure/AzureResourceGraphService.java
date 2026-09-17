package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.dto.ResourceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enumerates every resource in an Azure subscription with one Resource Graph query.
 *
 * <p>Replaces the shape of the inventory scan rather than one of its steps. {@code
 * AzureCloudListService} enumerates a subscription by issuing about thirty separate per-type
 * {@code list()} calls through the fluent SDK, on a fifteen-thread pool with a ninety-second budget.
 * Resource Graph answers the same question in one paginated KQL query, server-side.
 *
 * <p>What that changes:
 *
 * <ul>
 *   <li><b>Coverage stops depending on what someone remembered to write.</b> The fluent path can only
 *       see the types that have a hand-written fetcher. A {@code fetchGenericResources} catch-all was
 *       added to stop unmodelled types being dropped entirely, which is a sensible hedge but is a
 *       workaround for the architecture. Resource Graph returns everything ARM knows about, and
 *       {@link #friendlyType} derives a readable label for any type without an explicit one — so a
 *       new Azure service appears without a code change.
 *   <li><b>The ninety-second truncation cliff disappears.</b> The existing scan cancels outstanding
 *       tasks on timeout and returns partial results with a warning, so a large subscription is
 *       quietly incomplete. Paging with {@code $skipToken} has no such cliff.
 *   <li><b>Key Vault stops being quadratic.</b> {@code fetchKeyVaults} cannot list vaults
 *       subscription-wide through the path it uses, so it loops every resource group and lists
 *       generic resources inside each — O(resource groups) round trips for one type. Here it is one
 *       row in the same result set as everything else.
 *   <li><b>One ARM request instead of ~30.</b> The old fan-out is parallel, so wall-clock time was
 *       tolerable, but it is thirty times the requests per refresh per subscription against an API
 *       that throttles per subscription.
 * </ul>
 *
 * <p>Deliberately built on {@link AzureArmClient} rather than the
 * {@code azure-resourcemanager-resourcegraph} SDK module. Resource Graph is a single REST endpoint,
 * and that client already owns cached bearer tokens, timeouts, and the error semantics that matter
 * here — a 403 throws rather than reading as an empty subscription. Adding an SDK module would have
 * meant a new dependency to keep version-aligned for one HTTP call.
 *
 * <p>Requires the service principal to hold {@code Reader} on the subscription, which is the same
 * grant the fluent path needs. Returns an empty {@link Optional} — not an empty list — when the query
 * cannot be run, so the caller can tell "could not ask" from "nothing there".
 */
@Service
@Slf4j
public class AzureResourceGraphService {

    private static final String RESOURCE_GRAPH_URL =
            AzureArmClient.ARM_BASE + "/providers/Microsoft.ResourceGraph/resources?api-version=2022-10-01";

    /**
     * Rows per page. Resource Graph caps a single response at 1000.
     */
    private static final int PAGE_SIZE = 1000;

    /** Bound on pages followed, mirroring {@link AzureArmClient}'s own ceiling. */
    private static final int MAX_PAGES = 200;

    /**
     * Projects only the columns the cloudlist renders.
     *
     * <p>Projecting server-side rather than pulling whole resource bodies is most of the point: the
     * fluent path materialises a full model per resource to read four fields off it.
     */
    private static final String QUERY = String.join(" ",
            "Resources",
            "| project id, name, type, location, resourceGroup, tags,",
            "          sku = properties.sku, state = properties.provisioningState,",
            "          powerState = properties.extended.instanceView.powerState.displayStatus",
            "| order by type asc, name asc");

    private final AzureArmClient armClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureResourceGraphService(AzureArmClient armClient) {
        this.armClient = armClient;
    }

    /**
     * Enumerates the subscription.
     *
     * @return the resources, or empty when Resource Graph could not be queried at all — the caller
     *         must fall back rather than treat that as an empty subscription
     */
    public Optional<List<ResourceDto>> listAllResources(String subscriptionId,
            java.util.function.Function<String, String> labelResolver) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }

        long startMs = System.currentTimeMillis();
        List<ResourceDto> resources = new ArrayList<>();
        Map<String, Integer> skippedTypes = new HashMap<>();

        try {
            String token = armClient.getToken(subscriptionId);
            String skipToken = null;
            int pages = 0;

            do {
                Optional<String> body = armClient.post(RESOURCE_GRAPH_URL, token,
                        requestBody(subscriptionId, skipToken));
                if (body.isEmpty()) {
                    break;
                }

                JsonNode root = objectMapper.readTree(body.get());
                JsonNode data = root.path("data");
                if (data.isArray()) {
                    for (JsonNode row : data) {
                        ResourceDto dto = toResourceDto(row, labelResolver);
                        // A null label means the caller classes this type as noise or as a child
                        // resource represented by its parent — the same rule the per-type scan's
                        // catch-all applies, applied here from one place rather than two.
                        if (dto != null) {
                            resources.add(dto);
                        } else {
                            skippedTypes.merge(row.path("type").asText(""), 1, Integer::sum);
                        }
                    }
                }

                // Resource Graph spells the continuation token both ways depending on API version.
                skipToken = firstNonBlank(root.path("$skipToken").asText(null),
                        root.path("skipToken").asText(null));
                pages++;
            } while (skipToken != null && !skipToken.isBlank() && pages < MAX_PAGES);

            if (pages >= MAX_PAGES) {
                log.warn("[AzureResourceGraph] Hit the {}-page ceiling for subscription {}; results may "
                        + "be truncated", MAX_PAGES, subscriptionId);
            }

        } catch (Exception e) {
            // Empty Optional, not a partial list. A permission failure part-way through would
            // otherwise present as a subscription owning only what was read before the error.
            log.warn("[AzureResourceGraph] Query failed for subscription {} ({}). Falling back to the "
                    + "per-type scan. Grant the service principal Reader on the subscription to use "
                    + "the single-query path.", subscriptionId, e.getMessage());
            return Optional.empty();
        }

        if (!skippedTypes.isEmpty()) {
            log.debug("[AzureResourceGraph] Skipped {} noisy/child ARM type(s): {}",
                    skippedTypes.size(), skippedTypes);
        }

        if (resources.isEmpty()) {
            log.warn("[AzureResourceGraph] Returned no resources for subscription {} — falling back to "
                    + "the per-type scan to confirm", subscriptionId);
            return Optional.empty();
        }

        log.info("[AzureResourceGraph] {} resources for subscription {} in {}ms via Resource Graph",
                resources.size(), subscriptionId, System.currentTimeMillis() - startMs);
        return Optional.of(resources);
    }

    /** Builds the query envelope, carrying the continuation token when paging. */
    private String requestBody(String subscriptionId, String skipToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("subscriptions").add(subscriptionId);
        body.put("query", QUERY);

        ObjectNode options = body.putObject("options");
        options.put("$top", PAGE_SIZE);
        if (skipToken != null && !skipToken.isBlank()) {
            options.put("$skipToken", skipToken);
        }
        return body.toString();
    }

    /** @return the mapped resource, or null when the caller's resolver classes the type as skippable */
    private ResourceDto toResourceDto(JsonNode row, java.util.function.Function<String, String> labelResolver) {
        String armType = row.path("type").asText("");

        String label = labelResolver.apply(armType);
        if (label == null || label.isBlank()) {
            return null;
        }

        ResourceDto dto = new ResourceDto();
        dto.setId(row.path("id").asText(""));
        dto.setName(row.path("name").asText(""));
        dto.setType(label);
        dto.setRegion(textOrDefault(row, "location", "global"));

        // A VM's meaningful state is its power state, which lives under instance view; everything
        // else reports provisioningState. The fluent path made the same distinction per fetcher.
        String powerState = row.path("powerState").asText(null);
        dto.setState(firstNonBlank(powerState, row.path("state").asText(null), "Unknown"));

        Map<String, String> details = new HashMap<>();
        String resourceGroup = row.path("resourceGroup").asText(null);
        if (resourceGroup != null && !resourceGroup.isBlank()) {
            details.put("resourceGroup", resourceGroup);
        }
        JsonNode sku = row.path("sku");
        if (sku.isObject()) {
            String skuName = firstNonBlank(sku.path("name").asText(null), sku.path("tier").asText(null));
            if (skuName != null) {
                details.put("sku", skuName);
            }
        } else if (sku.isTextual() && !sku.asText().isBlank()) {
            details.put("sku", sku.asText());
        }
        details.put("armType", armType);
        dto.setDetails(details);

        return dto;
    }

    private static String textOrDefault(JsonNode row, String field, String fallback) {
        String value = row.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return null;
    }
}
