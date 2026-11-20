package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.pages.DocumentClassList;
import com.xammer.cloud.dto.matters.pages.RemediationActivity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Document Classes from {@code /saas/categories} and the Remediation page from {@code /remediation/activity}
 * (design 5.6.2). Dependency-free and defensive: a missing section is empty or null, never an exception.
 */
@Component
public class PagesMapper {

    static final int MAX_CATEGORIES = 100;
    static final int MAX_CLASS_ROWS = 2_000;
    static final int MAX_CLASS_TEXT = 200;
    static final int MAX_STATUS_COUNTS = 20;
    static final int MAX_REMEDIATION_ITEMS = 100;
    static final int MAX_APPROVERS = 20;

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    // ── Document Classes ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code categories[]{category, subcategories[]}} → one row per class in route order; the same class
     * twice in one category gives one row; categories de-duplicated in route order.
     */
    public DocumentClassList toDocumentClasses(JsonNode body) {
        Set<String> categories = new LinkedHashSet<>();
        Set<DocumentClassList.Row> rows = new LinkedHashSet<>();
        for (JsonNode node : body.path("categories")) {
            String category = boundedText(node.path("category"));
            if (category == null) {
                continue;
            }
            if (!categories.contains(category) && categories.size() >= MAX_CATEGORIES) {
                break;
            }
            categories.add(category);
            for (JsonNode sub : node.path("subcategories")) {
                if (rows.size() >= MAX_CLASS_ROWS) {
                    break;
                }
                String name = boundedText(sub);
                if (name != null) {
                    rows.add(new DocumentClassList.Row(name, category));
                }
            }
        }
        return new DocumentClassList(new ArrayList<>(categories), new ArrayList<>(rows));
    }

    private static String boundedText(JsonNode node) {
        String text = DrawerJson.stringValue(node);
        return text == null || text.length() > MAX_CLASS_TEXT ? null : text;
    }

    // ── Remediation ──────────────────────────────────────────────────────────────────────────────

    /** {@code response{summary, statusCounts, items[], total}}; {@code skip} and {@code limit} are the request's. */
    public RemediationActivity toRemediation(JsonNode body, int skip, int limit) {
        JsonNode response = body.path("response");
        JsonNode summary = response.path("summary");
        RemediationActivity.Summary cards = new RemediationActivity.Summary(
                DatastoreRowMapper.longOr(summary, "total", 0L),
                DatastoreRowMapper.longOr(summary, "waitingApproval", 0L),
                DatastoreRowMapper.longOr(summary, "inProgressScheduled", 0L),
                DatastoreRowMapper.longOr(summary, "completed", 0L),
                DatastoreRowMapper.longOr(summary, "failed", 0L));

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> counts = response.path("statusCounts").fields();
        while (counts.hasNext() && statusCounts.size() < MAX_STATUS_COUNTS) {
            Map.Entry<String, JsonNode> entry = counts.next();
            if (UPPER_SNAKE.matcher(entry.getKey()).matches() && entry.getValue().isNumber()) {
                statusCounts.put(entry.getKey(), entry.getValue().asLong());
            }
        }

        List<RemediationActivity.Item> items = new ArrayList<>();
        for (JsonNode node : response.path("items")) {
            if (items.size() >= MAX_REMEDIATION_ITEMS) {
                break;
            }
            items.add(toItem(node));
        }
        return new RemediationActivity(cards, statusCounts, DatastoreRowMapper.longOr(response, "total", 0L),
                skip, limit, items);
    }

    private static RemediationActivity.Item toItem(JsonNode node) {
        String actionType = DrawerJson.string(node, "action_type");
        String datastoreType = DrawerJson.string(node, "datastore_type");
        String triggerType = DrawerJson.string(node, "trigger_type");
        String status = DrawerJson.string(node, "status");
        return new RemediationActivity.Item(
                DrawerJson.string(node, "id"),
                actionType,
                PageLabels.remediationAction(actionType),
                DrawerJson.string(node, "object_name"),
                DrawerJson.string(node, "object_path"),
                datastoreType,
                DatastoreLabels.subtypeLabel(datastoreType),
                DrawerJson.string(node, "source_type"),
                person(node.path("initiated_by")),
                triggerType,
                PageLabels.remediationTrigger(triggerType),
                DrawerJson.epochSeconds(node.path("created_at")),
                status,
                PageLabels.remediationStatus(status),
                approvers(node),
                DrawerJson.epochSeconds(node.path("scheduled_at")));
    }

    /** An object gives its name and email; a string is the name. */
    private static RemediationActivity.Person person(JsonNode node) {
        if (node.isObject()) {
            String name = DrawerJson.string(node, "name");
            String email = DrawerJson.string(node, "email");
            return name == null && email == null ? null : new RemediationActivity.Person(name, email);
        }
        String text = DrawerJson.stringValue(node);
        return text == null ? null : new RemediationActivity.Person(text, null);
    }

    /** {@code approvers[]} (strings, or objects' name else email), with {@code approver} first when absent. */
    private static List<String> approvers(JsonNode node) {
        List<String> out = new ArrayList<>();
        String single = personText(node.path("approver"));
        for (JsonNode approver : node.path("approvers")) {
            String text = personText(approver);
            if (text != null && !out.contains(text)) {
                out.add(text);
            }
        }
        if (single != null && !out.contains(single)) {
            out.add(0, single);
        }
        return out.size() > MAX_APPROVERS ? new ArrayList<>(out.subList(0, MAX_APPROVERS)) : out;
    }

    private static String personText(JsonNode node) {
        if (node.isObject()) {
            String name = DrawerJson.string(node, "name");
            return name != null ? name : DrawerJson.string(node, "email");
        }
        return DrawerJson.stringValue(node);
    }
}
