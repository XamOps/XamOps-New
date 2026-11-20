package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.Sig;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;
import com.xammer.cloud.service.autospotting.spi.GroupDiscovery;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GCP discovery (design-core.md §2.5, AC-SPN-12): rules 1-5 over the project's MIGs, read only. First
 * matching rule wins:
 *
 * <ol>
 *   <li>a {@code spot_group} row exists for the key: identity check only;</li>
 *   <li>a template described {@code Spot template created by XamOps AutoSpotting} that provisions
 *       Spot: LEGACY_UNCONFIRMED with a reconstructed snapshot, {@code exact=false};</li>
 *   <li>a template described {@code XamOps AutoSpotting copy of ...} (or carrying the
 *       {@code xamops-autospotting} label) whose group id has no row: LEGACY_UNCONFIRMED,
 *       {@code ORPHAN_MARKER}, never managed automatically;</li>
 *   <li>a Spot template with no XamOps marker: {@code EXTERNAL_SPOT}, a read-time class with no row;</li>
 *   <li>anything else: nothing.</li>
 * </ol>
 * Names are hints only and are never evidence. A project that cannot be read, or whose {@code gcp_project_id}
 * is shared by two {@code CloudAccount} rows, is reported in {@code failedLocations} and skipped, never
 * read as "no groups" (FR-OBS-06, AC-SPN-14). No mutating call is made: the view is read only.
 */
@Component
public class GcpGroupDiscovery implements GroupDiscovery {
    public static final String LEGACY_DESCRIPTION = "Spot template created by XamOps AutoSpotting";
    public static final String COPY_DESCRIPTION_PREFIX = "XamOps AutoSpotting copy of ";
    public static final String GROUP_LABEL = "xamops-autospotting";

    private final GcpViewSource views;
    private final GroupLookup groupLookup;
    private final CloudAccountRepository accounts;

    public GcpGroupDiscovery(GcpViewSource views, GroupLookup groupLookup, CloudAccountRepository accounts) {
        this.views = views;
        this.groupLookup = groupLookup;
        this.accounts = accounts;
    }

    @Override
    public Provider provider() {
        return Provider.GCP;
    }

    @Override
    public DiscoveredGroup.Result discover(CloudAccount account, ReadOnlyCloudView ignored) {
        List<DiscoveredGroup> groups = new ArrayList<>();
        Map<Integer, Integer> counts = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        String project = account.getGcpProjectId();
        if (project == null || project.isBlank()) {
            return new DiscoveredGroup.Result(Provider.GCP, List.of(), counts, List.of("project"));
        }
        if (accounts.findByAwsAccountIdOrGcpProjectId(project, project).stream()
                .filter(a -> project.equals(a.getGcpProjectId())).count() > 1) {
            return new DiscoveredGroup.Result(Provider.GCP, List.of(), counts, List.of("DUPLICATE_PROVIDER_ACCOUNT:" + project));
        }
        Set<String> rowKeys = new HashSet<>();
        Set<String> rowIds = new HashSet<>();
        for (SpotGroup row : groupLookup.forAccount(Provider.GCP, account.getId())) {
            rowKeys.add(row.getGroupKey());
            rowIds.add(String.valueOf(row.getId()));
        }
        GcpReadOnlyView view;
        List<JsonNode> migs;
        try {
            view = views.view(account);
            migs = view.reads().aggregatedListMigs();
        } catch (RuntimeException e) {
            return new DiscoveredGroup.Result(Provider.GCP, List.of(), counts, List.of(project));
        }
        Map<String, Sig<JsonNode>> templateMemo = new HashMap<>();
        for (JsonNode raw : migs) {
            GcpMigFacts g = GcpMigFacts.from(project, raw);
            if (!g.locationKnown() || g.name() == null) {
                counts.merge(5, 1, Integer::sum);
                continue;
            }
            classify(view, g, templateMemo, rowKeys, rowIds, groups, counts, failed);
        }
        return new DiscoveredGroup.Result(Provider.GCP, groups, counts, failed);
    }

    private void classify(GcpReadOnlyView view, GcpMigFacts g, Map<String, Sig<JsonNode>> memo, Set<String> rowKeys,
                          Set<String> rowIds, List<DiscoveredGroup> out, Map<Integer, Integer> counts,
                          List<String> failed) {
        String key = g.groupKey();
        List<GcpSnapshotCodec.TemplateRead> templates = new ArrayList<>();
        boolean legacy = false;
        boolean copy = false;
        boolean spot = false;
        boolean labelled = false;
        String labelGroupId = null;
        boolean unreadable = false;
        for (String link : g.templateLinks()) {
            Sig<JsonNode> sig = memo.computeIfAbsent(link, l -> {
                try {
                    return Sig.ok(view.reads().getInstanceTemplate(l));
                } catch (RuntimeException e) {
                    return Sig.failed(GcpErrorMapper.classify(e));
                }
            });
            if (!(sig instanceof Sig.Ok<JsonNode> ok)) {
                unreadable = true;
                templates.add(new GcpSnapshotCodec.TemplateRead(link, null));
                continue;
            }
            JsonNode t = ok.value();
            templates.add(new GcpSnapshotCodec.TemplateRead(link, t));
            boolean templateSpot = isSpot(t);
            String description = t.path("description").asText("");
            legacy |= templateSpot && description.startsWith(LEGACY_DESCRIPTION);
            copy |= description.startsWith(COPY_DESCRIPTION_PREFIX);
            String label = t.path("properties").path("labels").path(GROUP_LABEL).asText(null);
            if (label != null) {
                labelled = true;
                labelGroupId = label;
            }
            spot |= templateSpot;
        }
        boolean marker = legacy || copy || labelled;
        // 1. An existing row: identity check only.
        if (rowKeys.contains(key)) {
            out.add(new DiscoveredGroup(key, g.id(), 1, null, null, null, marker, spot));
            counts.merge(1, 1, Integer::sum);
            return;
        }
        if (unreadable) {
            failed.add(g.project() + "/" + g.location());
            return;
        }
        // 2. The legacy Spot template.
        if (legacy) {
            out.add(new DiscoveredGroup(key, g.id(), 2, null, reconstructed(g, templates), null, true, spot));
            counts.merge(2, 1, Integer::sum);
            return;
        }
        // 3. A XamOps copy marker whose group id has no row.
        if ((copy || labelled) && (labelGroupId == null || !rowIds.contains(labelGroupId))) {
            out.add(new DiscoveredGroup(key, g.id(), 3, DiscoveredGroup.ORPHAN_MARKER, reconstructed(g, templates),
                    null, true, spot));
            counts.merge(3, 1, Integer::sum);
            return;
        }
        // 4. No XamOps marker and a Spot template: a read-time class, no row.
        if (!marker && spot) {
            counts.merge(4, 1, Integer::sum);
            return;
        }
        // 5. Anything else: nothing.
        counts.merge(5, 1, Integer::sum);
    }

    private static com.xammer.cloud.service.autospotting.spi.SnapshotPayload reconstructed(
            GcpMigFacts g, List<GcpSnapshotCodec.TemplateRead> templates) {
        return GcpSnapshotCodec.encode(g, null, templates, true, List.of("RECONSTRUCTED_FROM_DISCOVERY"));
    }

    /** A Spot template: {@code scheduling.provisioningModel=SPOT} (or the legacy {@code preemptible} flag). Never a name. */
    static boolean isSpot(JsonNode template) {
        JsonNode scheduling = template.path("properties").path("scheduling");
        return "SPOT".equals(scheduling.path("provisioningModel").asText())
                || scheduling.path("preemptible").asBoolean(false);
    }
}
