package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.util.SdkAutoConstructList;
import software.amazon.awssdk.core.util.SdkAutoConstructMap;

import java.util.List;
import java.util.Map;

/**
 * Renders an AWS SDK request as JSON (member names as AWS spells them, unset members absent) so a
 * golden file can pin exactly what a conversion sends. Test support only.
 */
final class SdkJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SdkJson() {
    }

    static JsonNode of(SdkPojo pojo) {
        ObjectNode out = MAPPER.createObjectNode();
        for (SdkField<?> f : pojo.sdkFields()) {
            Object v = f.getValueOrDefault(pojo);
            JsonNode n = node(v);
            if (n != null) {
                out.set(f.memberName(), n);
            }
        }
        return out;
    }

    private static JsonNode node(Object v) {
        if (v == null || v instanceof SdkAutoConstructList || v instanceof SdkAutoConstructMap) {
            return null;
        }
        if (v instanceof SdkPojo p) {
            return of(p);
        }
        if (v instanceof List<?> list) {
            ArrayNode a = MAPPER.createArrayNode();
            for (Object o : list) {
                JsonNode n = node(o);
                if (n != null) {
                    a.add(n);
                }
            }
            return a;
        }
        if (v instanceof Map<?, ?> map) {
            ObjectNode o = MAPPER.createObjectNode();
            map.forEach((k, val) -> {
                JsonNode n = node(val);
                if (n != null) {
                    o.set(String.valueOf(k), n);
                }
            });
            return o;
        }
        if (v instanceof Integer i) {
            return MAPPER.getNodeFactory().numberNode(i);
        }
        if (v instanceof Boolean b) {
            return MAPPER.getNodeFactory().booleanNode(b);
        }
        if (v instanceof Number n) {
            return MAPPER.getNodeFactory().numberNode(n.doubleValue());
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(v));
    }
}
