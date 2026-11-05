package com.xammer.cloud.service.envallocation;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.xammer.cloud.domain.envallocation.EnvAllocationResult;
import com.xammer.cloud.domain.envallocation.EnvAllocationRun;
import com.xammer.cloud.service.envallocation.AllocationModel.AllocationType;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchField;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchOperator;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.SplitSpec;
import com.xammer.cloud.service.envallocation.EnvAllocationRuleService.VersionSnapshot;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.Breakdown;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.EnvironmentTotal;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.RuleTotal;
import com.xammer.cloud.service.envallocation.EnvAllocationViews.Status;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The files an auditor is given: every allocated line as CSV, and a signed-off summary as PDF.
 *
 * <p>Both are produced only from stored runs. A preview can change between two clicks; a run
 * cannot, so a number in either file can always be traced to the run id printed beside it.
 */
@Service
public class EnvAllocationExportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DeviceRgb HEADER_BG = new DeviceRgb(243, 244, 246);

    private final EnvAllocationRunService runService;
    private final EnvAllocationRuleService ruleService;
    private final EnvAllocationAuditService audit;

    public EnvAllocationExportService(EnvAllocationRunService runService,
                                      EnvAllocationRuleService ruleService,
                                      EnvAllocationAuditService audit) {
        this.runService = runService;
        this.ruleService = ruleService;
        this.audit = audit;
    }

    public String fileStem(EnvAllocationRun run) {
        return "environment-allocation-" + run.getAwsAccountId() + "-" + run.getBillingPeriod()
                + "-v" + run.getVersionNumber() + "-run" + run.getId();
    }

    /**
     * Every result row, at full grain.
     *
     * <p>{@code source_cost} is what the grouped bill lines cost before the rule acted, and
     * {@code allocated_amount} the share this row received — so a split reads as
     * "2000.00 × 60% = 1200.00" on its own line, without the PDF.
     */
    public String csv(Long clientId, String actor, EnvAllocationRun run) {
        requireSucceeded(run);
        StringBuilder csv = new StringBuilder("run_id,aws_account_id,billing_period,cost_basis,"
                + "rule_version,locked,environment,allocation_kind,rule_position,rule_name,rule_id,"
                + "service,usage_type,region,charge_type,resource_id,match_detail,percent,source_cost,"
                + "allocated_amount,line_count\n");
        for (EnvAllocationResult r : runService.results(run.getId())) {
            csv.append(run.getId()).append(',')
                    .append(field(run.getAwsAccountId())).append(',')
                    .append(run.getBillingPeriod()).append(',')
                    .append(run.getCostBasis()).append(',')
                    .append(run.getVersionNumber()).append(',')
                    .append(run.isLocked()).append(',')
                    .append(field(r.getEnvironmentName())).append(',')
                    .append(r.getAllocationKind()).append(',')
                    .append(r.getRulePosition() == null ? "" : r.getRulePosition()).append(',')
                    .append(field(r.getRuleName())).append(',')
                    .append(field(r.getRuleId())).append(',')
                    .append(field(r.getService())).append(',')
                    .append(field(r.getUsageType())).append(',')
                    .append(field(r.getRegion())).append(',')
                    .append(field(r.getChargeType())).append(',')
                    .append(field(r.getResourceId())).append(',')
                    .append(field(r.getMatchDetail())).append(',')
                    .append(r.getPercent() == null ? "" : r.getPercent().toPlainString()).append(',')
                    .append(r.getSourceCost().toPlainString()).append(',')
                    .append(r.getAmount().toPlainString()).append(',')
                    .append(r.getLineCount())
                    .append('\n');
        }
        audit.record(clientId, actor, "EXPORTED_CSV", "RUN", String.valueOf(run.getId()),
                run.getAwsAccountId() + " " + run.getBillingPeriod());
        return csv.toString();
    }

    /** One run as a summary: totals per environment, every rule with what it took, and the reconciliation. */
    public byte[] pdf(Long clientId, String actor, EnvAllocationRun run, Breakdown breakdown) {
        requireSucceeded(run);
        if (breakdown.status() != Status.RUN) {
            throw EnvAllocationException.conflict("This run has no stored result to export.");
        }
        VersionSnapshot version = ruleService.versionById(run.getRuleVersionId());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Closing the Document closes the PdfDocument and writer beneath it.
        try (Document doc = new Document(new PdfDocument(new PdfWriter(out)), PageSize.A4)) {
            doc.setMargins(36, 36, 36, 36);

            doc.add(new Paragraph("Environment cost allocation").setFontSize(16).setBold());
            doc.add(new Paragraph("AWS account " + run.getAwsAccountId() + " · " + run.getBillingPeriod()
                    + " · " + run.getCostBasis() + " cost").setFontSize(10));

            Table meta = new Table(UnitValue.createPercentArray(new float[]{30, 70})).useAllAvailableWidth();
            meta.setFontSize(9);
            metaRow(meta, "Run", "#" + run.getId() + (run.isLocked() ? " (locked)" : ""));
            metaRow(meta, "Rule version", "v" + version.versionNumber() + ", published "
                    + ts(version.publishedAt()) + (version.publishedBy() == null ? "" : " by " + version.publishedBy())
                    + (version.note() == null ? "" : " — " + version.note()));
            metaRow(meta, "Run by", (run.getStartedBy() == null ? "" : run.getStartedBy())
                    + ", finished " + ts(run.getFinishedAt()));
            if (run.isLocked()) {
                metaRow(meta, "Locked", ts(run.getLockedAt()) + " by " + run.getLockedBy());
            }
            metaRow(meta, "Bill lines read", String.valueOf(run.getLineCount()));
            if (run.getError() != null) {
                metaRow(meta, "Note", run.getError());
            }
            doc.add(meta.setMarginTop(8));

            doc.add(new Paragraph("Totals by environment").setFontSize(12).setBold().setMarginTop(14));
            Table totals = new Table(UnitValue.createPercentArray(new float[]{34, 17, 17, 17, 15}))
                    .useAllAvailableWidth().setFontSize(9);
            header(totals, "Environment", "Direct", "Split", "Total", "Share");
            for (EnvironmentTotal e : breakdown.environments()) {
                totals.addCell(cell(e.name()));
                totals.addCell(money(e.direct()));
                totals.addCell(money(e.split()));
                totals.addCell(money(e.total()).setBold());
                totals.addCell(right(e.share().toPlainString() + "%"));
            }
            totals.addCell(cell("Bill total").setBold());
            totals.addCell(cell(""));
            totals.addCell(cell(""));
            totals.addCell(money(breakdown.totalCost()).setBold());
            totals.addCell(right("100%"));
            doc.add(totals);

            BigDecimal sum = breakdown.environments().stream().map(EnvironmentTotal::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            doc.add(new Paragraph("Reconciliation: the environments above add up to "
                    + amount(sum) + " against a bill total of " + amount(breakdown.totalCost())
                    + " read from the Cost and Usage Report for this account and month."
                    + " Direct means a label or a rule sent the whole amount to one environment;"
                    + " split means a rule divided it by percentage or in proportion to the rest.")
                    .setFontSize(8).setFontColor(ColorConstants.DARK_GRAY).setMarginTop(4));

            doc.add(new Paragraph("Rules (version " + version.versionNumber() + "), first match wins")
                    .setFontSize(12).setBold().setMarginTop(14));
            Table rules = new Table(UnitValue.createPercentArray(new float[]{5, 22, 36, 22, 15}))
                    .useAllAvailableWidth().setFontSize(8);
            header(rules, "#", "Rule", "Matches", "Allocates to", "Took");
            for (RuleTotal t : breakdown.rules()) {
                rules.addCell(cell(String.valueOf(t.position())));
                rules.addCell(cell(t.name()));
                rules.addCell(cell(describeMatch(t.rule())));
                rules.addCell(cell(describeAllocation(t.rule())));
                rules.addCell(t.matched() ? money(t.matchedCost()) : right("nothing"));
            }
            doc.add(rules);

            addEnvironmentDetail(doc, breakdown, runService.results(run.getId()));

            doc.add(new Paragraph("Every allocated line, with the rule that produced it, is in the CSV"
                    + " export of run #" + run.getId() + ".")
                    .setFontSize(8).setFontColor(ColorConstants.DARK_GRAY).setMarginTop(8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the PDF: " + e.getMessage(), e);
        }
        audit.record(clientId, actor, "EXPORTED_PDF", "RUN", String.valueOf(run.getId()),
                run.getAwsAccountId() + " " + run.getBillingPeriod());
        return out.toByteArray();
    }

    // ── Detail by environment ───────────────────────────────────────────────────

    /** One resource's share of one service inside one environment, summed across its lines. */
    private static final class ResourceShare {
        BigDecimal amount = BigDecimal.ZERO;
        final Set<String> how = new LinkedHashSet<>();
    }

    private static final class ServiceShare {
        BigDecimal amount = BigDecimal.ZERO;
        final Map<String, ResourceShare> resources = new LinkedHashMap<>();
    }

    /**
     * Every environment, then every service in it, then every resource in that service, each with
     * its amount and how it got there.
     *
     * <p>Built from the stored result rows, the same rows the CSV lists, so the PDF and the CSV agree
     * line for line. A resource can reach one environment several ways — a label for its compute, a
     * split for its data transfer — so its rows are summed and every way is listed.
     *
     * <p>Rows with no resource id are charges that belong to no resource (tax, support, fees), or
     * the whole run when it was not read per resource. The two are told apart run-wide: a run with
     * any resource id was read per resource.
     */
    private void addEnvironmentDetail(Document doc, Breakdown breakdown, List<EnvAllocationResult> results) {
        boolean perResource = results.stream()
                .anyMatch(r -> r.getResourceId() != null && !r.getResourceId().isBlank());
        String noResource = perResource
                ? "Charges with no resource (tax, support, fees)"
                : "No resource breakdown";

        Map<String, Map<String, ServiceShare>> byEnv = new LinkedHashMap<>();
        for (EnvAllocationResult r : results) {
            if (r.getAmount() == null || r.getAmount().signum() == 0) {
                continue;
            }
            String service = r.getService() == null || r.getService().isBlank() ? "Other" : r.getService();
            String resource = r.getResourceId() == null || r.getResourceId().isBlank()
                    ? noResource : r.getResourceId();
            ServiceShare svc = byEnv.computeIfAbsent(r.getEnvironmentName(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(service, k -> new ServiceShare());
            svc.amount = svc.amount.add(r.getAmount());
            ResourceShare res = svc.resources.computeIfAbsent(resource, k -> new ResourceShare());
            res.amount = res.amount.add(r.getAmount());
            res.how.add(how(r));
        }

        doc.add(new Paragraph("Detail by environment").setFontSize(12).setBold().setMarginTop(14));
        doc.add(new Paragraph("Every service in each environment, and every resource in each service, with"
                + " how its cost got there. Amounts are this environment's share only: a resource split"
                + " 60/40 appears under both environments with its share of each.")
                .setFontSize(8).setFontColor(ColorConstants.DARK_GRAY));
        if (!perResource) {
            doc.add(new Paragraph("This run did not read the bill per resource, so services are listed"
                    + " without their resources. That happens when no rule uses a XamOps label or a"
                    + " resource id, and on runs made before resource detail was stored. Run the month"
                    + " again with such a rule to list resources.")
                    .setFontSize(8).setFontColor(ColorConstants.DARK_GRAY));
        }

        // Environments in the order of the totals table; any name only the rows know comes last.
        List<String> order = new ArrayList<>();
        breakdown.environments().forEach(e -> order.add(e.name()));
        byEnv.keySet().stream().filter(n -> !order.contains(n)).forEach(order::add);

        for (String envName : order) {
            Map<String, ServiceShare> services = byEnv.get(envName);
            if (services == null || services.isEmpty()) {
                continue;
            }
            BigDecimal envTotal = services.values().stream().map(s -> s.amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int resourceCount = services.values().stream()
                    .mapToInt(s -> (int) s.resources.keySet().stream().filter(k -> !k.equals(noResource)).count())
                    .sum();

            doc.add(new Paragraph(envName + " — " + amount(envTotal) + " · " + services.size()
                    + (services.size() == 1 ? " service" : " services")
                    + (perResource ? " · " + resourceCount + (resourceCount == 1 ? " resource" : " resources") : ""))
                    .setFontSize(10).setBold().setMarginTop(10));

            Table t = new Table(UnitValue.createPercentArray(new float[]{62, 22, 16}))
                    .useAllAvailableWidth().setFontSize(7);
            header(t, "Service / resource", "How", "Amount");
            services.entrySet().stream()
                    .sorted((a, b) -> b.getValue().amount.compareTo(a.getValue().amount))
                    .forEach(se -> {
                        ServiceShare svc = se.getValue();
                        t.addCell(cell(se.getKey()).setBold().setBackgroundColor(SERVICE_BG));
                        t.addCell(cell("").setBackgroundColor(SERVICE_BG));
                        t.addCell(money(svc.amount).setBold().setBackgroundColor(SERVICE_BG));
                        svc.resources.entrySet().stream()
                                .sorted((a, b) -> b.getValue().amount.compareTo(a.getValue().amount))
                                .forEach(re -> {
                                    t.addCell(cell(re.getKey()).setPaddingLeft(14));
                                    t.addCell(cell(String.join(", ", re.getValue().how)));
                                    t.addCell(money(re.getValue().amount));
                                });
                    });
            doc.add(t);
        }
    }

    private static final DeviceRgb SERVICE_BG = new DeviceRgb(249, 250, 251);

    /** "Label", "Rule", "Split 60%", "Proportional 22.22%", "Unallocated". */
    private static String how(EnvAllocationResult r) {
        String kind = switch (r.getAllocationKind()) {
            case "TAGGED" -> "Label";
            case "RULE" -> "Rule";
            case "SPLIT" -> "Split";
            case "PROPORTIONAL" -> "Proportional";
            case "UNALLOCATED" -> "Unallocated";
            default -> r.getAllocationKind();
        };
        boolean divided = "SPLIT".equals(r.getAllocationKind()) || "PROPORTIONAL".equals(r.getAllocationKind());
        if (divided && r.getPercent() != null) {
            return kind + " " + r.getPercent().stripTrailingZeros().toPlainString() + "%";
        }
        return kind;
    }

    // ── Wording ─────────────────────────────────────────────────────────────────

    static String describeMatch(RuleSpec r) {
        if (r.field() == MatchField.ANY) {
            return "Every remaining line";
        }
        String subject = switch (r.field()) {
            case SERVICE -> "Service";
            case USAGE_TYPE -> "Usage type";
            case REGION -> "Region";
            case ACCOUNT -> "Account";
            case CHARGE_TYPE -> "Charge type";
            case RESOURCE_ID -> "Resource id";
            case TAG -> "Tag " + r.tagKey();
            case LABEL -> "XamOps label";
            default -> r.field().name();
        };
        if (r.operator() == MatchOperator.EXISTS) {
            return subject + (r.field() == MatchField.LABEL ? " is set" : " has a value");
        }
        String op = switch (r.operator()) {
            case EQUALS -> "equals";
            case CONTAINS -> "contains";
            case STARTS_WITH -> "starts with";
            default -> "";
        };
        return subject + " " + op + " \"" + r.value() + "\"";
    }

    static String describeAllocation(RuleSpec r) {
        if (r.allocation() == AllocationType.FROM_VALUE) {
            return "The environment the matched value names";
        }
        if (r.allocation() == AllocationType.PROPORTIONAL) {
            return "In proportion to every other allocation";
        }
        List<SplitSpec> splits = r.splits() == null ? List.of() : r.splits();
        return splits.stream()
                .map(s -> s.percent().stripTrailingZeros().toPlainString() + "% " + s.environmentName())
                .collect(Collectors.joining(", "));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static void requireSucceeded(EnvAllocationRun run) {
        if (!EnvAllocationRun.SUCCEEDED.equals(run.getStatus())) {
            throw EnvAllocationException.conflict("Only a finished run can be exported.");
        }
    }

    /** Quotes every field: rule and service names are free text and may carry commas. */
    private static String field(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        // A leading =, +, - or @ is read as a formula by spreadsheet software.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static void header(Table t, String... names) {
        for (String n : names) {
            t.addHeaderCell(new Cell().add(new Paragraph(n).setBold()).setBackgroundColor(HEADER_BG));
        }
    }

    private static void metaRow(Table t, String key, String value) {
        t.addCell(new Cell().add(new Paragraph(key).setBold()));
        t.addCell(new Cell().add(new Paragraph(value == null ? "" : value)));
    }

    private static Cell cell(String text) {
        return new Cell().add(new Paragraph(text == null ? "" : text));
    }

    private static Cell right(String text) {
        return new Cell().add(new Paragraph(text).setTextAlignment(TextAlignment.RIGHT));
    }

    private static Cell money(BigDecimal value) {
        return right(amount(value));
    }

    private static String amount(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_EVEN);
        return String.format(Locale.US, "$%,.2f", v);
    }

    private static String ts(java.time.LocalDateTime t) {
        return t == null ? "" : t.format(TS);
    }
}
