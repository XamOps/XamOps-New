package com.xammer.cloud.service;

import com.itextpdf.kernel.colors.Color;
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
import com.xammer.cloud.service.LabelSpendService.Item;
import com.xammer.cloud.service.LabelSpendService.LabelTotal;
import com.xammer.cloud.service.LabelSpendService.Spend;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Label spend for one month as a PDF: every label, or one.
 *
 * <p>Built from the same {@link Spend} the label bar on the Charges by service card reads, so a
 * figure in the file is the figure on the screen. The month is divided the same way: resource
 * labels take their shares first, service labels share what is left of each line, and what is
 * left after that is unallocated.
 *
 * <p>Text is kept to the characters the built-in PDF fonts carry, since a label name is the only
 * thing in the file a user typed.
 */
@Service
public class LabelSpendPdfService {

    private static final DeviceRgb HEADER_BG = new DeviceRgb(243, 244, 246);
    private static final DeviceRgb MUTED = new DeviceRgb(107, 114, 128);
    private static final DeviceRgb UNALLOCATED = new DeviceRgb(209, 213, 219);
    private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    /**
     * @param labelId   one label, or null for every label
     * @param labelName that label's name, for the title when it has no spend this month
     * @param currency  ISO code the figures are in; the bill's own currency
     * @param generatedBy who asked for the file, printed on it
     */
    public byte[] pdf(Spend spend, String accountId, Long labelId, String labelName, String currency,
                      String generatedBy) {
        LabelTotal only = null;
        if (labelId != null) {
            only = spend.labels().stream().filter(l -> labelId.equals(l.labelId())).findFirst().orElse(null);
        }
        NumberFormat money = moneyFormat(currency);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Closing the Document closes the PdfDocument and writer beneath it.
        try (Document doc = new Document(new PdfDocument(new PdfWriter(out)), PageSize.A4)) {
            doc.setMargins(36, 36, 36, 36);

            String title = labelId == null ? "Label spend - all labels"
                    : "Label spend - " + safe(only != null ? only.name()
                            : labelName != null ? labelName : "label #" + labelId);
            doc.add(new Paragraph(title).setFontSize(16).setBold());
            doc.add(new Paragraph("AWS account " + accountId + " | " + monthText(spend.period())
                    + (isOpen(spend.period()) ? " (so far)" : "") + " | " + spend.basis() + " cost")
                    .setFontSize(10).setFontColor(MUTED));

            Table meta = new Table(UnitValue.createPercentArray(new float[]{28, 72})).useAllAvailableWidth();
            meta.setFontSize(9).setMarginTop(8);
            metaRow(meta, "Generated", GENERATED.format(ZonedDateTime.now(ZoneOffset.UTC))
                    + (generatedBy == null ? "" : " by " + safe(generatedBy)));
            metaRow(meta, "Bill total", money.format(spend.billTotal()));
            metaRow(meta, "Labelled (all labels)", money.format(spend.allocatedTotal()) + " (" + pct(share(spend.allocatedTotal(), spend.billTotal())) + ")");
            metaRow(meta, "Unallocated", money.format(spend.unallocatedTotal()) + " (" + pct(share(spend.unallocatedTotal(), spend.billTotal())) + ")");
            metaRow(meta, "How the bill is divided", "Each bill line goes first to the labels on its resource, by their"
                    + " shares; what is left goes to the labels on its service, by their shares; anything still left"
                    + " is unallocated. No cost is counted under two labels.");
            doc.add(meta);

            if (labelId == null) {
                summary(doc, spend, money);
                for (LabelTotal l : spend.labels()) {
                    if (l.itemCount() > 0) section(doc, l, money);
                }
            } else if (only == null || only.itemCount() == 0) {
                doc.add(new Paragraph("Nothing billed in " + monthText(spend.period()) + " carries this label.")
                        .setFontSize(10).setMarginTop(16));
            } else {
                section(doc, only, money);
            }
        }
        return out.toByteArray();
    }

    /** One row per label, then the unallocated remainder and the bill total. */
    private static void summary(Document doc, Spend spend, NumberFormat money) {
        doc.add(new Paragraph("Totals by label").setFontSize(12).setBold().setMarginTop(16));
        Table t = new Table(UnitValue.createPercentArray(new float[]{4, 44, 14, 18, 20})).useAllAvailableWidth();
        t.setFontSize(9);
        header(t, "", "Label", "Resources", "% of bill", "Amount");
        for (LabelTotal l : spend.labels()) {
            t.addCell(swatch(color(l.color())));
            t.addCell(cell(safe(l.name())));
            t.addCell(cell(String.valueOf(l.resourceCount())).setTextAlignment(TextAlignment.RIGHT));
            t.addCell(cell(pct(l.share())).setTextAlignment(TextAlignment.RIGHT));
            t.addCell(cell(money.format(l.total())).setTextAlignment(TextAlignment.RIGHT));
        }
        t.addCell(swatch(UNALLOCATED));
        t.addCell(cell("Unallocated"));
        t.addCell(cell(""));
        t.addCell(cell(pct(share(spend.unallocatedTotal(), spend.billTotal()))).setTextAlignment(TextAlignment.RIGHT));
        t.addCell(cell(money.format(spend.unallocatedTotal())).setTextAlignment(TextAlignment.RIGHT));

        t.addCell(cell("").setBackgroundColor(HEADER_BG));
        t.addCell(cell("Bill total").setBold().setBackgroundColor(HEADER_BG));
        t.addCell(cell("").setBackgroundColor(HEADER_BG));
        t.addCell(cell("100%").setBold().setBackgroundColor(HEADER_BG).setTextAlignment(TextAlignment.RIGHT));
        t.addCell(cell(money.format(spend.billTotal())).setBold().setBackgroundColor(HEADER_BG)
                .setTextAlignment(TextAlignment.RIGHT));
        doc.add(t);
    }

    /** One label: its total, then every resource and resource-less charge it took a share of. */
    private static void section(Document doc, LabelTotal l, NumberFormat money) {
        doc.add(new Paragraph(safe(l.name())).setFontSize(12).setBold().setMarginTop(18)
                .setFontColor(color(l.color())));
        doc.add(new Paragraph(money.format(l.total()) + " | " + pct(l.share()) + " of the bill | "
                + l.resourceCount() + (l.resourceCount() == 1 ? " resource" : " resources"))
                .setFontSize(9).setFontColor(MUTED));

        Table t = new Table(UnitValue.createPercentArray(new float[]{34, 24, 14, 14, 14})).useAllAvailableWidth();
        t.setFontSize(8);
        header(t, "Resource", "Service | region", "How", "Of", "Amount");
        for (Item i : l.items()) {
            String what = i.resourceId() == null || i.resourceId().isBlank()
                    ? "Charges with no resource"
                    : i.name() != null && !i.name().isBlank() ? safe(i.name()) + "\n" + safe(i.resourceId()) : safe(i.resourceId());
            String where = safe(i.service()) + (i.resourceId() == null || i.resourceId().isBlank() ? "" : " | " + safe(i.region()));
            t.addCell(cell(what));
            t.addCell(cell(where));
            t.addCell(cell(how(i)));
            t.addCell(cell(money.format(i.sourceCost())).setTextAlignment(TextAlignment.RIGHT));
            t.addCell(cell(money.format(i.amount())).setTextAlignment(TextAlignment.RIGHT));
        }
        doc.add(t);
        if (l.itemCount() > l.items().size()) {
            doc.add(new Paragraph("Listing the " + l.items().size() + " costliest of " + l.itemCount()
                    + ". The total above covers all of them.").setFontSize(8).setFontColor(MUTED));
        }
    }

    private static String how(Item i) {
        String via = switch (i.via() == null ? "" : i.via()) {
            case "resource" -> "Resource label";
            case "service" -> "Service label";
            default -> "Resource + service";
        };
        BigDecimal p = i.percent();
        return p == null || p.compareTo(BigDecimal.valueOf(100)) == 0 ? via : via + ", " + pct(p);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static void header(Table t, String... titles) {
        for (String h : titles) {
            t.addHeaderCell(cell(h).setBold().setBackgroundColor(HEADER_BG));
        }
    }

    private static void metaRow(Table t, String key, String value) {
        t.addCell(cell(key).setFontColor(MUTED));
        t.addCell(cell(value));
    }

    private static Cell cell(String text) {
        return new Cell().add(new Paragraph(text == null ? "" : text)).setPadding(4);
    }

    private static Cell swatch(Color c) {
        return new Cell().add(new Paragraph(" ")).setBackgroundColor(c).setPadding(4);
    }

    /** A label's stored "#rrggbb", or grey for anything else. */
    private static Color color(String hex) {
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) {
            int v = Integer.parseInt(hex.substring(1), 16);
            return new DeviceRgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
        }
        return ColorConstants.GRAY;
    }

    private static NumberFormat moneyFormat(String currency) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        try {
            f.setCurrency(Currency.getInstance(currency == null ? "USD" : currency.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            f.setCurrency(Currency.getInstance("USD"));
        }
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f;
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_EVEN);
    }

    private static String pct(BigDecimal p) {
        return (p == null ? BigDecimal.ZERO : p).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "%";
    }

    private static String monthText(String period) {
        try {
            return MONTH.format(YearMonth.parse(period));
        } catch (RuntimeException e) {
            return period;
        }
    }

    private static boolean isOpen(String period) {
        try {
            return !YearMonth.parse(period).isBefore(YearMonth.now(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Characters the built-in fonts can draw. Anything outside Latin-1 becomes "?", rather than
     * silently vanishing and leaving a label name that reads as a different one.
     */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> b.append(cp == '\n' || (cp >= 0x20 && cp <= 0xFF && (cp < 0x7F || cp > 0x9F)) ? (char) cp : '?'));
        return b.toString();
    }
}
