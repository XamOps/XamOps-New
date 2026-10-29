package com.xammer.billops.service.upload;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionMetadata;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the AWS Billing console's bill printout ("AWS estimated bill summary", Bills page
 * → Print) into a {@link ParsedAwsBill}.
 *
 * <p>The printout's "Charges by service" is a four-level table, and each level is set in
 * its own type, so the parser reads the layout rather than guessing from wording:
 * <pre>
 *   Amazon Web Services India Private Limited (26)   Total pre-tax USD 9,893.89   provider  14pt, left margin
 *   Simple Storage Service                                      USD 2,527.13   service   12pt bold
 *   Asia Pacific (Mumbai)                                       USD 2,527.13   region    10pt bold
 *     Amazon Simple Storage Service APS3-Requests-Tier1           USD 735.64   group     9pt
 *       $0.005 per 1,000 PUT, COPY, ...   147,128,739 Requests    USD 735.64   rate line 9pt, indented
 * </pre>
 * Usage quantity and amount sit in their own columns, located from each page's
 * "Description / Usage Quantity / Amount in USD" header. Credits print in parentheses.
 * Descriptions and quantities that wrap continue on rows with no amount.
 */
@Component
public class AwsBillPdfParser {

    private static final Logger logger = LoggerFactory.getLogger(AwsBillPdfParser.class);

    private static final String MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec";

    /**
     * "Jun 1 - Jun 30, 2026" (also "June 1 – 30, 2026"). Month is group 1, year group 5.
     * Up to 60 characters may separate the day range from its year, for printouts whose
     * narrow header columns wrap the year onto the next line; the year must be a
     * standalone 20xx so a 12-digit account id is never read as one.
     */
    private static final Pattern PERIOD_RANGE = Pattern.compile(
            "\\b(" + MONTHS + ")[a-z]*\\.?\\s+(\\d{1,2})\\s*-\\s*(?:(" + MONTHS + ")[a-z]*\\.?\\s+)?(\\d{1,2})(?:,?\\s*|,[\\s\\S]{0,60}?)(?<!\\d)(20\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** "Billing period June 2026": the fallback when no day range is printed. */
    private static final Pattern PERIOD_MONTH_YEAR = Pattern.compile(
            "billing\\s+period[:\\s]+(" + MONTHS + ")[a-z]*\\.?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    /** "Jul 3, 2026". */
    private static final Pattern DATE = Pattern.compile(
            "\\b(" + MONTHS + ")[a-z]*\\.?\\s+(\\d{1,2}),\\s*(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    /** A 12-digit AWS account id, optionally printed as 1234-5678-9012. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("(?<![\\d-])(\\d{4}-?\\d{4}-?\\d{4})(?![\\d-])");
    private static final Pattern ACCOUNT_LABEL = Pattern.compile("account\\s+id", Pattern.CASE_INSENSITIVE);

    private static final String MONEY = "(?:USD|US\\$|\\$)\\s*(-?[0-9][0-9,]*\\.[0-9]{2,})";
    private static final Pattern GRAND_TOTAL = Pattern.compile(
            "grand\\s+total:?[^\\n]{0,80}?" + MONEY, Pattern.CASE_INSENSITIVE);

    /** One amount cell: "USD 1,234.56", "(USD 1,220.30)", "-$0.42". */
    private static final Pattern AMOUNT_CELL = Pattern.compile(
            "^(\\()?\\s*(-)?\\s*(?:USD|US\\$|\\$)\\s*(-?[0-9][0-9,]*\\.[0-9]{2,})\\s*(\\))?$");

    /** One quantity cell: "147,128,739 Requests", "0.073 GiBps-mo", "17,350 Finding Ingestion". */
    private static final Pattern QUANTITY_CELL = Pattern.compile("^(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(.*)$");

    private static final Pattern PROVIDER_COUNT = Pattern.compile("\\(\\d+\\)");
    private static final Pattern PRE_TAX_LABEL = Pattern.compile("total\\s+pre-?\\s?tax", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAX_LABEL = Pattern.compile(
            "^(?:tax|taxes|gst|igst|cgst|sgst|utgst|vat|estimated\\s+tax)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The bill names services without their brand prefix ("Elastic Compute Cloud",
     * "CloudFront"). The names that drive invoice logic -- CloudFront gets its own row and
     * discount scope -- must match what CUR-built invoices carry; see
     * CurIngestionService.normaliseServiceName.
     */
    private static final Map<String, String> SERVICE_NAMES = Map.of(
            "cloudfront", "Amazon CloudFront",
            "elastic compute cloud", "Amazon Elastic Compute Cloud",
            "simple storage service", "Amazon Simple Storage Service",
            "relational database service", "Amazon Relational Database Service",
            "data transfer", "AWS Data Transfer");

    /** Display name (lower-cased) to region code, built once from the SDK's region metadata. */
    private final Map<String, String> regionCodes = buildRegionCodes();

    // ─── Layout capture ──────────────────────────────────────────────────────

    /** A run of text the PDF placed as one cell. */
    record Segment(float x, float size, boolean bold, String text) {
    }

    /** One visual line of the page, left to right. */
    record Row(int page, float pageWidth, List<Segment> segments) {
        Segment first() { return segments.get(0); }
        String text() {
            StringJoiner j = new StringJoiner(" ");
            segments.forEach(s -> j.add(s.text()));
            return j.toString();
        }
    }

    /** Collects rows with each cell's position, size and weight. */
    private static final class RowCollector extends PDFTextStripper {
        final List<Row> rows = new ArrayList<>();
        private List<Segment> current = new ArrayList<>();
        private int page;
        private float pageWidth;

        RowCollector() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void startPage(PDPage p) throws IOException {
            page++;
            pageWidth = p.getMediaBox().getWidth();
            super.startPage(p);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            String clean = normalise(text).trim();
            if (clean.isEmpty() || positions.isEmpty()) {
                return;
            }
            TextPosition f = positions.get(0);
            String font = f.getFont() != null && f.getFont().getName() != null ? f.getFont().getName() : "";
            current.add(new Segment(f.getXDirAdj(), f.getFontSizeInPt(),
                    font.toLowerCase(Locale.ROOT).contains("bold"), clean));
        }

        @Override
        protected void writeLineSeparator() {
            flush();
        }

        @Override
        protected void endPage(PDPage p) throws IOException {
            flush();
            super.endPage(p);
        }

        private void flush() {
            if (!current.isEmpty()) {
                current.sort(Comparator.comparingDouble(Segment::x));
                rows.add(new Row(page, pageWidth, current));
                current = new ArrayList<>();
            }
        }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public ParsedAwsBill parse(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            RowCollector collector = new RowCollector();
            collector.getText(document);
            ParsedAwsBill bill = parseRows(collector.rows);
            bill.setPageCount(document.getNumberOfPages());
            logger.info("[BillUpload] Parsed AWS bill PDF: {} pages, account={}, period={}, {} lines, lineTotal={}, expected={}",
                    bill.getPageCount(), bill.getAccountId(), bill.getBillingPeriod(), bill.getLines().size(),
                    bill.lineTotal(), bill.expectedLineTotal());
            return bill;
        }
    }

    ParsedAwsBill parseRows(List<Row> rows) {
        ParsedAwsBill bill = new ParsedAwsBill();
        StringBuilder all = new StringBuilder();
        rows.forEach(r -> all.append(r.text()).append('\n'));
        String text = all.toString();

        bill.setEstimated(text.toLowerCase(Locale.ROOT).contains("estimated bill"));
        readPeriod(text, bill);
        bill.setAccountId(readAccountId(text));
        Matcher grand = GRAND_TOTAL.matcher(text);
        if (grand.find()) {
            bill.setGrandTotal(money(grand.group(1)));
        }

        ChargesReader reader = new ChargesReader(bill);
        reader.read(rows);
        reconcile(bill, reader.summaryMarketplace, reader.dropped);

        if (bill.getAccountId() == null) {
            bill.getWarnings().add("No AWS account ID was found in the PDF. Choose the account to bill.");
        }
        if (bill.getBillingPeriod() == null) {
            bill.getWarnings().add("No billing period was found in the PDF. Choose the month this bill is for.");
        }
        return bill;
    }

    // ─── Header ──────────────────────────────────────────────────────────────

    private static String normalise(String raw) {
        return raw.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2009', ' ')
                .replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
                .replaceAll("\\s+", " ");
    }

    private static void readPeriod(String text, ParsedAwsBill bill) {
        Matcher range = PERIOD_RANGE.matcher(text);
        if (range.find()) {
            bill.setBillingPeriod(YearMonth.of(Integer.parseInt(range.group(5)), monthOf(range.group(1))));
            // "Date printed" is the first full date after the period's day range.
            Matcher date = DATE.matcher(text);
            if (date.find(range.end(4))) {
                try {
                    bill.setDatePrinted(LocalDate.of(Integer.parseInt(date.group(3)), monthOf(date.group(1)),
                            Integer.parseInt(date.group(2))));
                } catch (Exception ignored) {
                    // Display only.
                }
            }
            return;
        }
        Matcher monthYear = PERIOD_MONTH_YEAR.matcher(text);
        if (monthYear.find()) {
            bill.setBillingPeriod(YearMonth.of(Integer.parseInt(monthYear.group(2)), monthOf(monthYear.group(1))));
        }
    }

    private static int monthOf(String name) {
        String key = name.substring(0, 3).toLowerCase(Locale.ROOT);
        return List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                .indexOf(key) + 1;
    }

    /** The first 12-digit id after an "Account ID" label, else the first one anywhere. */
    private static String readAccountId(String text) {
        Matcher label = ACCOUNT_LABEL.matcher(text);
        while (label.find()) {
            String window = text.substring(label.end(), Math.min(text.length(), label.end() + 300));
            Matcher id = ACCOUNT_ID.matcher(window);
            if (id.find()) {
                return id.group(1).replace("-", "");
            }
        }
        Matcher any = ACCOUNT_ID.matcher(text);
        return any.find() ? any.group(1).replace("-", "") : null;
    }

    // ─── Charges by service ──────────────────────────────────────────────────

    private enum Section { BEFORE, CHARGES, AFTER }

    private enum Level { NONE, SERVICE, REGION, GROUP, RATE }

    /** A row split into its three columns. */
    private record Cells(String description, String quantity, BigDecimal amount) {
    }

    private final class ChargesReader {
        private final ParsedAwsBill bill;
        BigDecimal summaryMarketplace;
        int dropped;

        private Section section = Section.BEFORE;
        private float quantityX = -1;
        private Float groupIndent;
        private Float rateIndent;

        private BigDecimal preTax;
        private BigDecimal tax;
        private boolean marketplace;
        private String provider;

        private String service;
        private BigDecimal serviceAmount;
        private boolean skipService;
        private int ratesInService;
        private String regionLabel;
        private BigDecimal regionAmount;
        private int ratesInRegion;
        private String group;
        private BigDecimal groupAmount;
        private int ratesInGroup;
        private BigDecimal providerAmount;
        private BigDecimal sumInGroup = BigDecimal.ZERO;
        private BigDecimal sumInRegion = BigDecimal.ZERO;
        private BigDecimal sumInService = BigDecimal.ZERO;
        private BigDecimal sumInProvider = BigDecimal.ZERO;

        private Level last = Level.NONE;
        /** Index of the last line added, or -1 when the last rate row was not kept. */
        private int lastLine = -1;

        ChargesReader(ParsedAwsBill bill) {
            this.bill = bill;
        }

        void read(List<Row> rows) {
            for (Row row : rows) {
                Segment head = row.first();
                String lower = row.text().toLowerCase(Locale.ROOT);

                if (head.size() >= 16f) { // Section heading.
                    if (lower.startsWith("charges by service")) {
                        section = Section.CHARGES;
                    } else if (section == Section.CHARGES) {
                        closeProvider();
                        section = Section.AFTER;
                    }
                    last = Level.NONE;
                    continue;
                }
                if (isColumnHeader(row)) {
                    continue;
                }
                if (lower.startsWith("no data to display")) {
                    continue;
                }

                if (section == Section.BEFORE) {
                    readSummaryRow(row, lower);
                } else if (section == Section.CHARGES) {
                    readChargeRow(row, lower);
                }
            }
            if (section == Section.CHARGES) {
                closeProvider();
            }
            if (preTax != null) {
                bill.setPreTaxTotal(preTax);
            }
            if (tax != null) {
                bill.setTaxTotal(tax);
            }
        }

        /** "Description | Usage Quantity | Amount in USD", repeated at the top of every page. */
        private boolean isColumnHeader(Row row) {
            String first = row.first().text().toLowerCase(Locale.ROOT);
            if (!first.equals("description") && !first.equals("service provider") && !first.equals("service name")) {
                return false;
            }
            for (Segment s : row.segments()) {
                if (s.text().toLowerCase(Locale.ROOT).startsWith("usage quantity")) {
                    quantityX = s.x();
                }
            }
            return true;
        }

        /** The summary's Marketplace provider row, the only Marketplace figure some bills print. */
        private void readSummaryRow(Row row, String lower) {
            if (summaryMarketplace == null && lower.contains("marketplace") && !lower.contains("non-payable")) {
                BigDecimal amount = cells(row).amount();
                if (amount != null) {
                    summaryMarketplace = amount;
                }
            }
        }

        private void readChargeRow(Row row, String lower) {
            Segment head = row.first();
            Cells cells = cells(row);

            // Provider rows and "Total tax": the section's own top level.
            if (head.size() >= 13f) {
                last = Level.NONE;
                if (lower.startsWith("total tax")) {
                    closeService();
                    if (cells.amount() != null) {
                        tax = (tax == null ? BigDecimal.ZERO : tax).add(cells.amount());
                    }
                } else if (PRE_TAX_LABEL.matcher(lower).find() || PROVIDER_COUNT.matcher(lower).find()) {
                    closeProvider();
                    provider = providerName(row.text());
                    marketplace = provider.toLowerCase(Locale.ROOT).contains("marketplace");
                    providerAmount = cells.amount();
                    if (cells.amount() != null) {
                        preTax = (preTax == null ? BigDecimal.ZERO : preTax).add(cells.amount());
                    }
                } else {
                    closeService();
                }
                return;
            }

            if (cells.amount() == null) {
                continuation(cells, head);
                return;
            }

            if (head.bold() && head.size() >= 11f) {
                openService(cells.description(), cells.amount());
            } else if (head.bold() && head.size() >= 9.5f) {
                closeRegion();
                regionLabel = cells.description();
                regionAmount = cells.amount();
                last = Level.REGION;
            } else if (isRate(head, cells)) {
                rate(head, cells);
            } else {
                closeGroup();
                group = cells.description();
                groupAmount = cells.amount();
                if (groupIndent == null) {
                    groupIndent = head.x();
                }
                last = Level.GROUP;
            }
        }

        /** A rate line has a quantity, or sits at the rate lines' indent, deeper than its group. */
        private boolean isRate(Segment head, Cells cells) {
            if (!cells.quantity().isEmpty()) {
                return true;
            }
            if (rateIndent != null && Math.abs(head.x() - rateIndent) < 3f) {
                return true;
            }
            return groupIndent != null && group != null && head.x() > groupIndent + 4f;
        }

        private void rate(Segment head, Cells cells) {
            if (rateIndent == null && !cells.quantity().isEmpty()) {
                rateIndent = head.x();
            }
            ratesInService++;
            ratesInRegion++;
            ratesInGroup++;
            last = Level.RATE;
            lastLine = -1;

            BigDecimal cost = cells.amount();
            if (skipService || TAX_LABEL.matcher(cells.description()).find()) {
                return; // BillOps adds its own GST.
            }
            // $0.00 lines are kept too, so the draft lists every charge the bill does.

            String qty = "1";
            String unit = "N/A";
            Matcher m = QUANTITY_CELL.matcher(cells.quantity());
            if (m.matches()) {
                qty = m.group(1).replace(",", "");
                unit = m.group(2).isBlank() ? "N/A" : m.group(2).trim();
            } else if (!cells.quantity().isEmpty()) {
                unit = cells.quantity();
            }
            String desc = cells.description().isEmpty()
                    ? (group != null ? group : service + " usage") : cells.description();
            add(new ParsedAwsBill.Line(provider, service, regionCode(regionLabel), group, desc, qty, unit, cost,
                    marketplace, false));
            lastLine = bill.getLines().size() - 1;
        }

        /** A row with no amount: the wrapped rest of the row above it. */
        private void continuation(Cells cells, Segment head) {
            String desc = cells.description();
            String qty = cells.quantity();
            switch (last) {
                case RATE -> {
                    if (lastLine < 0) {
                        return; // Belonged to a line that was not kept.
                    }
                    ParsedAwsBill.Line l = bill.getLines().get(lastLine);
                    String newDesc = desc.isEmpty() ? l.description() : join(l.description(), desc);
                    String newUnit = qty.isEmpty() ? l.unit()
                            : ("N/A".equals(l.unit()) ? qty : join(l.unit(), qty));
                    bill.getLines().set(lastLine, new ParsedAwsBill.Line(l.provider(), l.service(), l.region(),
                            l.group(), newDesc, l.quantity(), newUnit, l.cost(), l.marketplace(), l.synthetic()));
                }
                case GROUP -> group = join(group, desc);
                case REGION -> regionLabel = join(regionLabel, desc);
                case SERVICE -> {
                    if (head.bold()) {
                        service = normaliseService(join(service, desc));
                    }
                }
                case NONE -> { /* Nothing to continue. */ }
            }
        }

        /** Splits a row into description, quantity and amount by column. */
        private Cells cells(Row row) {
            List<Segment> segs = row.segments();
            BigDecimal amount = null;
            int end = segs.size();
            Segment lastSeg = segs.get(segs.size() - 1);
            Matcher m = AMOUNT_CELL.matcher(lastSeg.text());
            if (m.matches() && (segs.size() > 1 || lastSeg.x() > row.pageWidth() * 0.6f)) {
                BigDecimal v = money(m.group(3));
                boolean negative = (m.group(1) != null && m.group(4) != null) || m.group(2) != null;
                amount = negative ? v.abs().negate() : v;
                end--;
            }
            float qx = quantityX > 0 ? quantityX : row.pageWidth() * 0.55f;
            StringJoiner desc = new StringJoiner(" ");
            StringJoiner qty = new StringJoiner(" ");
            for (int i = 0; i < end; i++) {
                Segment s = segs.get(i);
                (s.x() >= qx - 10f ? qty : desc).add(s.text());
            }
            return new Cells(desc.toString().trim(), qty.toString().trim(), amount);
        }

        private void openService(String label, BigDecimal amount) {
            closeService();
            service = normaliseService(label);
            serviceAmount = amount;
            skipService = TAX_LABEL.matcher(label).find();
            last = Level.SERVICE;
        }

        private void add(ParsedAwsBill.Line line) {
            bill.getLines().add(line);
            sumInGroup = sumInGroup.add(line.cost());
            sumInRegion = sumInRegion.add(line.cost());
            sumInService = sumInService.add(line.cost());
            sumInProvider = sumInProvider.add(line.cost());
        }

        /**
         * Adds a rounding line when the lines under a level don't add up to the subtotal
         * the bill printed for it. Applied bottom-up -- group, region, service, provider --
         * so each level's lines, rounding included, sum to exactly its printed figure, and
         * every printed line stays as printed.
         */
        private void round(BigDecimal printed, BigDecimal sum, String region, String usageGroup, String svc) {
            if (printed == null || skipService) {
                return;
            }
            BigDecimal diff = printed.subtract(sum);
            if (diff.signum() != 0) {
                add(new ParsedAwsBill.Line(provider, svc, region, usageGroup, ParsedAwsBill.ROUNDING_DESCRIPTION,
                        "1", "N/A", diff, marketplace, false, true));
            }
        }

        // A level that printed a subtotal and nothing under it -- collapsed in the console
        // when printed -- still has to be billed, so it becomes one line.

        private void closeGroup() {
            if (group != null && ratesInGroup == 0 && nonZero(groupAmount) && !skipService) {
                add(new ParsedAwsBill.Line(provider, service, regionCode(regionLabel), null, group, "1", "N/A",
                        groupAmount, marketplace, true));
            } else if (group != null && ratesInGroup > 0) {
                round(groupAmount, sumInGroup, regionCode(regionLabel), group, service);
            }
            group = null;
            groupAmount = null;
            ratesInGroup = 0;
            sumInGroup = BigDecimal.ZERO;
        }

        private void closeRegion() {
            closeGroup();
            if (regionLabel != null && ratesInRegion == 0 && nonZero(regionAmount) && !skipService) {
                add(new ParsedAwsBill.Line(provider, service, regionCode(regionLabel), null, service + " charges",
                        "1", "N/A", regionAmount, marketplace, true));
            } else if (regionLabel != null && ratesInRegion > 0) {
                round(regionAmount, sumInRegion, regionCode(regionLabel), null, service);
            }
            sumInGroup = BigDecimal.ZERO; // A region-level rounding line belongs to no group.
            regionLabel = null;
            regionAmount = null;
            ratesInRegion = 0;
            sumInRegion = BigDecimal.ZERO;
        }

        private void closeService() {
            closeRegion();
            if (service != null && ratesInService == 0 && nonZero(serviceAmount) && !skipService) {
                add(new ParsedAwsBill.Line(provider, service, null, null, service + " charges", "1", "N/A",
                        serviceAmount, marketplace, true));
            } else if (service != null && ratesInService > 0) {
                round(serviceAmount, sumInService, ParsedAwsBill.ROUNDING_REGION, null, service);
            }
            sumInGroup = BigDecimal.ZERO;
            sumInRegion = BigDecimal.ZERO;
            service = null;
            serviceAmount = null;
            skipService = false;
            ratesInService = 0;
            sumInService = BigDecimal.ZERO;
        }

        /** Closes a service provider's block, rounding its services to its "Total pre-tax". */
        private void closeProvider() {
            closeService();
            round(providerAmount, sumInProvider, ParsedAwsBill.ROUNDING_REGION, null, ParsedAwsBill.ROUNDING_REGION);
            sumInGroup = BigDecimal.ZERO;
            sumInRegion = BigDecimal.ZERO;
            sumInService = BigDecimal.ZERO;
            sumInProvider = BigDecimal.ZERO;
            providerAmount = null;
        }
    }

    private static String providerName(String rowText) {
        String name = PRE_TAX_LABEL.split(rowText, 2)[0];
        return PROVIDER_COUNT.matcher(name).replaceAll("").trim();
    }

    // ─── Reconciliation ──────────────────────────────────────────────────────

    private static void reconcile(ParsedAwsBill bill, BigDecimal summaryMarketplace, int dropped) {
        List<ParsedAwsBill.Line> lines = bill.getLines();
        BigDecimal expected = bill.expectedLineTotal();

        boolean hasMpLines = lines.stream().anyMatch(ParsedAwsBill.Line::marketplace);
        BigDecimal mpFromLines = lines.stream().filter(ParsedAwsBill.Line::marketplace)
                .map(ParsedAwsBill.Line::cost).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (lines.isEmpty()) {
            BigDecimal total = expected != null ? expected : BigDecimal.ZERO;
            BigDecimal mp = summaryMarketplace != null ? summaryMarketplace.max(BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal rest = total.subtract(mp);
            if (rest.signum() != 0) {
                lines.add(new ParsedAwsBill.Line(null, "Amazon Web Services", null, null,
                        "AWS charges (from uploaded bill)", "1", "N/A", rest, false, true));
            }
            if (mp.signum() > 0) {
                lines.add(new ParsedAwsBill.Line(null, "AWS Marketplace", null, null,
                        "AWS Marketplace charges", "1", "N/A", mp, true, true));
                bill.setMarketplaceTotal(mp);
            }
            if (!lines.isEmpty()) {
                bill.getWarnings().add("No individual charges could be read from the PDF, so the draft has one line "
                        + "for the bill's total. Add the service breakdown on the draft if you need it.");
            }
            return;
        }

        if (hasMpLines) {
            bill.setMarketplaceTotal(mpFromLines.signum() > 0 ? mpFromLines : null);
        } else if (summaryMarketplace != null && summaryMarketplace.signum() > 0) {
            // Billed in the summary, absent from "Charges by service": added as one line
            // so the draft's total matches the bill.
            lines.add(new ParsedAwsBill.Line(null, "AWS Marketplace", null, null,
                    "AWS Marketplace charges", "1", "N/A", summaryMarketplace, true, true));
            bill.setMarketplaceTotal(summaryMarketplace);
        }

        if (lines.stream().anyMatch(ParsedAwsBill.Line::synthetic)) {
            bill.getWarnings().add("Some sections printed only a subtotal, so each became a single line. "
                    + "Expand every service in the AWS console before printing to get the full breakdown.");
        }

        BigDecimal target = expected;
        if (target == null) {
            return;
        }
        if (!hasMpLines && bill.getMarketplaceTotal() != null && bill.getPreTaxTotal() != null) {
            target = target.add(bill.getMarketplaceTotal()); // Summary-only Marketplace was added above.
        }
        // Every line is printed rounded to the cent, so the sum may drift from the printed
        // total by up to half a cent per line.
        BigDecimal tolerance = new BigDecimal("0.02").max(new BigDecimal("0.005").multiply(BigDecimal.valueOf(lines.size())));
        BigDecimal diff = bill.lineTotal().subtract(target).abs();
        if (diff.compareTo(tolerance) > 0) {
            bill.getWarnings().add(String.format(Locale.ROOT,
                    "The line items add up to $%,.2f but the bill's total is $%,.2f. Review the line items on the draft.",
                    bill.lineTotal(), target));
        }
        if (dropped > 0) {
            logger.debug("[BillUpload] Left out {} zero-amount lines", dropped);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** "Asia Pacific (Mumbai)" to "ap-south-1", so the detail bill names it the usual way. */
    private String regionCode(String label) {
        if (label == null || label.isBlank()) {
            return "Global";
        }
        String code = regionCodes.get(label.trim().toLowerCase(Locale.ROOT));
        return code != null ? code : label.trim();
    }

    private static Map<String, String> buildRegionCodes() {
        Map<String, String> map = new HashMap<>();
        try {
            for (Region region : Region.regions()) {
                RegionMetadata metadata = region.metadata();
                if (metadata == null || metadata.description() == null || region.isGlobalRegion()) {
                    continue;
                }
                String name = metadata.description().trim();
                map.putIfAbsent(name.toLowerCase(Locale.ROOT), region.id());
                // Bills still print "EU (Ireland)" where the SDK says "Europe (Ireland)".
                if (name.startsWith("Europe (")) {
                    map.putIfAbsent(("EU (" + name.substring("Europe (".length())).toLowerCase(Locale.ROOT), region.id());
                }
            }
        } catch (Exception e) {
            logger.warn("[BillUpload] Could not load AWS region metadata: {}", e.getMessage());
        }
        return map;
    }

    private static String normaliseService(String label) {
        String trimmed = label == null ? "AWS" : label.trim();
        String mapped = SERVICE_NAMES.get(trimmed.toLowerCase(Locale.ROOT));
        return mapped != null ? mapped : trimmed;
    }

    /** Rejoins a line the PDF wrapped; a word broken at a hyphen is rejoined without a space. */
    private static String join(String head, String tail) {
        if (head == null || head.isEmpty()) {
            return tail;
        }
        if (tail == null || tail.isEmpty()) {
            return head;
        }
        return head.endsWith("-") ? head + tail : head + " " + tail;
    }

    private static boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private static BigDecimal money(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }
}
