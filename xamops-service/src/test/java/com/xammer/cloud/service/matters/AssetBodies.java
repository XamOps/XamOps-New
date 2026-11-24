package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Synthetic {@code /api/v1/assets} bodies for the build 6 service tests. Every value is invented. */
final class AssetBodies {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AssetBodies() {
    }

    /** One synthetic asset row. */
    record Asset(String id, String category, String subtype, String provider, String account, long records) {
    }

    static Asset asset(int n, String category, String subtype, String provider, String account, long records) {
        return new Asset(String.format("%024x", n), category, subtype, provider, account, records);
    }

    /** {@code {"data": [...], "pagination": {"total": total}}}. */
    static JsonNode page(long total, List<Asset> assets) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode data = body.putArray("data");
        for (Asset asset : assets) {
            ObjectNode row = data.addObject();
            row.put("id", asset.id());
            row.put("source_id", asset.id());
            row.put("name", "store-" + asset.id().substring(18));
            row.put("category", asset.category());
            row.put("subtype", asset.subtype());
            row.putObject("risk").put("level", "high").put("score", 7.5);
            row.putObject("sensitives").put("record_count", asset.records());
            ObjectNode source = row.putObject("source");
            source.put("provider", asset.provider());
            source.put("account", asset.account());
            source.put("region", "test-region-1");
            row.putObject("scan_status").put("status", "CLASSIFIED");
        }
        body.putObject("pagination").put("total", total);
        return body;
    }
}
