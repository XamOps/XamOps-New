package com.xammer.cloud.service.compliance;

import com.xammer.cloud.service.security.ProwlerFrameworks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many requirements each compliance framework actually contains.
 *
 * <p>
 * This is the denominator the framework cards were missing. Without it a card could say
 * "43 clauses mapped — 62%" while staying silent about whether 43 is most of the framework or a
 * tenth of it, so every framework rendered an equally confident percentage no matter how little
 * of it the scan had touched.
 *
 * <p>
 * <strong>Where the numbers come from.</strong> Each count is the length of the {@code Requirements}
 * array in Prowler's own compliance definition for the exact framework id
 * {@link ProwlerFrameworks} requests — {@code prowler/compliance/<provider>/<id>.json} in the
 * Prowler source tree. That is deliberately not the control count a standards body publishes:
 * the question a card answers is "how much of what Prowler evaluates for this framework did this
 * scan settle", and only Prowler's own requirement list can answer it. Two useful sanity checks
 * fall out anyway — {@code cis_3.0_aws} is 62, matching the 62 recommendations published for CIS
 * AWS Foundations Benchmark v3.0.0, and the ISO 27001:2022 files are 92 against 93 published
 * Annex A controls.
 *
 * <p>
 * <strong>Why keyed on provider plus family rather than the raw id.</strong> Findings do not carry
 * the {@code --compliance} id. Prowler's OCSF output labels each mapping with a short framework
 * name such as {@code CIS-3.0}, {@code PCI-DSS} or {@code ISO-27001}, so the lookup normalises to
 * a family and resolves the version from the provider. That works precisely because
 * {@link ProwlerFrameworks} requests exactly one version of each family per provider — and the
 * versions genuinely differ, with AWS on PCI 3.2.1 / ISO 27001:2013 / NIST CSF 1.1 while GCP and
 * Azure are on PCI 4.0 / ISO 27001:2022.
 *
 * <p>
 * <strong>Failing closed.</strong> {@link #requirementCount} returns null rather than a guess for
 * an unknown provider, an unmapped family, or a raw name whose embedded version contradicts the
 * one on record. A missing denominator degrades the card to its previous behaviour; a wrong
 * denominator is a false statement about an audit scope, which is strictly worse.
 *
 * <p>
 * These counts are tied to the Prowler version in the deployment image. Re-verify on a Prowler
 * bump, the same way {@link ProwlerFrameworks} has to be.
 */
public final class ComplianceFrameworkBaseline {

    private ComplianceFrameworkBaseline() {}

    /** Prowler framework id → number of requirements defined in its compliance file. */
    private static final Map<String, Integer> REQUIREMENTS = Map.ofEntries(
            // AWS — see ProwlerFrameworks.AWS
            Map.entry("cis_3.0_aws", 62),
            Map.entry("soc2_aws", 27),
            Map.entry("pci_3.2.1_aws", 147),
            Map.entry("nist_csf_1.1_aws", 56),
            Map.entry("aws_foundational_security_best_practices_aws", 286),
            Map.entry("iso27001_2013_aws", 79),

            // GCP — see ProwlerFrameworks.GCP
            Map.entry("cis_2.0_gcp", 84),
            Map.entry("soc2_gcp", 28),
            Map.entry("pci_4.0_gcp", 1672),
            Map.entry("iso27001_2022_gcp", 92),
            Map.entry("mitre_attack_gcp", 46),
            Map.entry("hipaa_gcp", 23),

            // Azure — see ProwlerFrameworks.AZURE
            Map.entry("cis_3.0_azure", 159),
            Map.entry("soc2_azure", 26),
            Map.entry("pci_4.0_azure", 1669),
            Map.entry("iso27001_2022_azure", 92),
            Map.entry("mitre_attack_azure", 46),
            Map.entry("hipaa_azure", 34)
    );

    /**
     * provider → family → Prowler framework id.
     *
     * <p>
     * Derived from {@link ProwlerFrameworks} rather than restated, so adding a framework to a scan
     * without giving it a baseline here shows up as a null denominator instead of a stale one.
     */
    private static final Map<String, Map<String, String>> BY_PROVIDER = buildProviderIndex();

    private static Map<String, Map<String, String>> buildProviderIndex() {
        Map<String, Map<String, String>> out = new HashMap<>();
        out.put("AWS", index(ProwlerFrameworks.AWS));
        out.put("GCP", index(ProwlerFrameworks.GCP));
        out.put("AZURE", index(ProwlerFrameworks.AZURE));
        return out;
    }

    private static Map<String, String> index(String[] frameworkIds) {
        Map<String, String> byFamily = new HashMap<>();
        for (String id : frameworkIds) {
            String family = family(id);
            if (family != null) {
                // First declaration wins. A provider asking for two versions of one family would
                // make the version ambiguous, and silently picking one would be a guess.
                byFamily.putIfAbsent(family, id);
            }
        }
        return byFamily;
    }

    /**
     * Collapses a framework id or a scanner-reported name onto a family.
     *
     * <p>
     * Mirrors {@code frameworkKey} in {@code complianceTokens.ts}, restricted to the families that
     * actually have a baseline. Order matters: NIST 800-53 has to be tested before NIST CSF, and
     * ISO 27001 before a bare ISO.
     */
    static String family(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Separators are normalised to spaces first. The same framework arrives as
        // "aws_foundational_security_best_practices_aws" from the scan argument and as
        // "AWS-Foundational-Security-Best-Practices" in a finding, and matching the literal
        // underscore form silently missed the hyphenated one. Digits survive, because NIST 800-53
        // and ISO 27001 are told apart by theirs.
        String u = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ");

        if (u.contains("FOUNDATIONAL SECURITY") || u.contains("FSBP")) return "FSBP";
        if (u.contains("CIS")) return "CIS";
        if (u.contains("SOC")) return "SOC2";
        if (u.contains("PCI")) return "PCI";
        if (u.contains("HIPAA")) return "HIPAA";
        if (u.contains("NIST") && u.contains("800")) return "NIST800";
        if (u.contains("NIST")) return "NIST_CSF";
        if (u.contains("ISO") && u.contains("27001")) return "ISO27001";
        if (u.contains("MITRE")) return "MITRE";
        return null;
    }

    /** Matches a dotted or underscored version token, e.g. "3.0" in "CIS-3.0". */
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    /**
     * Total requirements in the framework this mapping belongs to, or null when it cannot be
     * established with confidence.
     *
     * @param provider      AWS | GCP | Azure, as recorded on the scan
     * @param rawFramework  framework name exactly as the scanner reported it, e.g. "CIS-3.0"
     */
    public static Integer requirementCount(String provider, String rawFramework) {
        if (provider == null || rawFramework == null) return null;

        Map<String, String> byFamily = BY_PROVIDER.get(provider.toUpperCase(Locale.ROOT));
        if (byFamily == null) return null;

        String family = family(rawFramework);
        if (family == null) return null;

        String frameworkId = byFamily.get(family);
        if (frameworkId == null) return null;

        // If the scanner named a version, it has to be the version we have a count for. A
        // Prowler upgrade that moves CIS 3.0 to 4.0 must blank the denominator, not misreport it.
        Matcher m = VERSION.matcher(rawFramework);
        if (m.find() && !frameworkId.contains(m.group(1))) return null;

        return REQUIREMENTS.get(frameworkId);
    }

    /** The Prowler framework id a mapping resolves to, for provenance. Null when unresolved. */
    public static String resolvedFrameworkId(String provider, String rawFramework) {
        if (provider == null || rawFramework == null) return null;
        Map<String, String> byFamily = BY_PROVIDER.get(provider.toUpperCase(Locale.ROOT));
        if (byFamily == null) return null;
        String family = family(rawFramework);
        return family == null ? null : byFamily.get(family);
    }
}
