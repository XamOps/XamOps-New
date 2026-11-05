package com.xammer.cloud.xampulse.service.insight;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Template sentences with real numbers (1 to 3 per overview). No model calls; every figure comes from the inputs. */
@Service("xpNarrativeBuilder")
public class NarrativeBuilder {

    public record Facts(int hostsTotal, int hostsReporting, int hostsCritical, int hostsWarning,
                        double errorsPerMin, int openIncidents, String topHost, String topReason) {}

    public List<String> build(Facts f) {
        List<String> out = new ArrayList<>();
        if (f.hostsTotal() == 0) {
            out.add("No servers are connected yet. Finish setup to start collecting logs and metrics.");
            return out;
        }
        if (f.hostsReporting() == 0) {
            out.add(String.format(Locale.ROOT, "None of your %d connected servers has reported data recently.", f.hostsTotal()));
            return out;
        }
        out.add(String.format(Locale.ROOT, "%d of %d servers are reporting.", f.hostsReporting(), f.hostsTotal()));
        if (f.hostsCritical() > 0 || f.hostsWarning() > 0) {
            StringBuilder sb = new StringBuilder();
            if (f.hostsCritical() > 0) {
                sb.append(f.hostsCritical()).append(f.hostsCritical() == 1 ? " server is" : " servers are").append(" critical");
            }
            if (f.hostsWarning() > 0) {
                if (sb.length() > 0) {
                    sb.append(" and ");
                }
                sb.append(f.hostsWarning()).append(f.hostsWarning() == 1 ? " needs" : " need").append(" a look");
            }
            sb.append('.');
            if (f.topHost() != null && f.topReason() != null) {
                sb.append(" Worst: ").append(f.topHost()).append(" (").append(f.topReason()).append(").");
            }
            out.add(sb.toString());
        } else {
            out.add("All reporting servers look healthy.");
        }
        if (f.openIncidents() > 0) {
            out.add(String.format(Locale.ROOT, "%d open %s, with %.1f errors per minute across your logs.",
                    f.openIncidents(), f.openIncidents() == 1 ? "incident" : "incidents", f.errorsPerMin()));
        } else if (f.errorsPerMin() >= 1) {
            out.add(String.format(Locale.ROOT, "Logs show %.1f errors per minute and no open incidents.", f.errorsPerMin()));
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }
}
