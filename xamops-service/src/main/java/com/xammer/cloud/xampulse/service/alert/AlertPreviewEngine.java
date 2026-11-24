package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.dto.XpAlertPreview;
import com.xammer.cloud.xampulse.dto.XpFiring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * Replays a rule once a minute over past values with the live {@link AlertStateMachine} ("for", flap guard,
 * no-data policy) and the {@link NotificationRouter}'s dedupe, so what it reports is what alerting would have done.
 */
public final class AlertPreviewEngine {

    /** How the rule decides: comparison, "for", missing-data policy and the repeat-notification window. */
    public record Config(String op, double threshold, Duration hold, AlertStateMachine.NoDataPolicy noData, Duration dedupe) {}

    private AlertPreviewEngine() {}

    /**
     * @param values           per group, evaluation minute (epoch seconds, the last complete minute) to the value the
     *                         rule compared; absent means no data that minute
     * @param observedFromStart groups the evaluator observes at every evaluation even without data (log-count and
     *                         heartbeat rules cover a fixed set of instances); other groups start once they have data
     * @param fill             value used for an observed-from-start group without data (0 for log counts), or null
     */
    public static XpAlertPreview replay(Map<String, ? extends NavigableMap<Long, Double>> values, Collection<String> observedFromStart,
                                        Double fill, long fromMin, long lastMin, Config cfg, int maxSeries) {
        Set<String> groups = new TreeSet<>(values.keySet());
        groups.addAll(observedFromStart);
        boolean high = cfg.op().startsWith(">");
        List<XpFiring> firings = new ArrayList<>();
        List<Ranked> ranked = new ArrayList<>();
        int evaluated = 0;
        int notified = 0;
        int resolved = 0;
        int suppressed = 0;
        int quiet = 0;
        for (String g : groups) {
            NavigableMap<Long, Double> vs = values.get(g);
            boolean always = observedFromStart.contains(g);
            boolean seen = always;
            AlertStateMachine.Current cur = AlertStateMachine.Current.initial(Instant.ofEpochSecond(fromMin));
            Instant episodeFrom = null;
            Instant firedAt = null;
            boolean episodeNotified = false;
            double worst = Double.NaN;
            Instant lastSent = null;
            int groupFirings = 0;
            double extreme = Double.NaN;
            List<double[]> pts = new ArrayList<>();
            for (long m = fromMin; m <= lastMin; m += 60) {
                Double v = vs == null ? null : vs.get(m);
                if (v == null && always && fill != null) {
                    v = fill;
                }
                if (!seen && v == null) {
                    continue;
                }
                seen = true;
                Instant now = Instant.ofEpochSecond(m + 60);
                AlertStateMachine.Outcome outcome = v == null ? AlertStateMachine.Outcome.NO_DATA
                        : (compare(v, cfg.op(), cfg.threshold()) ? AlertStateMachine.Outcome.BREACH : AlertStateMachine.Outcome.OK);
                AlertStateMachine.State before = cur.state();
                AlertStateMachine.Transition t = AlertStateMachine.step(cur, outcome, cfg.noData(), cfg.hold(), now);
                cur = new AlertStateMachine.Current(t.state(), t.since());
                evaluated++;
                if (v != null) {
                    pts.add(new double[] {(m + 60) * 1000.0, v});
                    extreme = Double.isNaN(extreme) ? v : (high ? Math.max(extreme, v) : Math.min(extreme, v));
                }
                boolean wasActive = before == AlertStateMachine.State.PENDING || before == AlertStateMachine.State.FIRING;
                boolean active = t.state() == AlertStateMachine.State.PENDING || t.state() == AlertStateMachine.State.FIRING;
                if (active && !wasActive) {
                    episodeFrom = now;
                    firedAt = null;
                    episodeNotified = false;
                    worst = Double.NaN;
                }
                if (active && v != null) {
                    worst = Double.isNaN(worst) ? v : (high ? Math.max(worst, v) : Math.min(worst, v));
                }
                if (t.fire()) {
                    firedAt = now;
                    if (lastSent != null && Duration.between(lastSent, now).compareTo(cfg.dedupe()) < 0) {
                        suppressed++;
                    } else {
                        notified++;
                        lastSent = now;
                        episodeNotified = true;
                    }
                } else if (t.quietReopen()) {
                    firedAt = now;
                    quiet++;
                }
                if (t.resolve()) {
                    resolved++;
                    if (firedAt != null) {
                        firings.add(new XpFiring(episodeFrom, firedAt, now, Map.of("group", g), Double.isNaN(worst) ? null : worst, false, episodeNotified));
                        groupFirings++;
                    }
                    episodeFrom = null;
                    firedAt = null;
                } else if (!active) {
                    episodeFrom = null; // a breach that ended before "for" elapsed never fired
                    firedAt = null;
                }
            }
            if (cur.state() == AlertStateMachine.State.FIRING && firedAt != null) {
                firings.add(new XpFiring(episodeFrom, firedAt, Instant.ofEpochSecond(lastMin + 60), Map.of("group", g), Double.isNaN(worst) ? null : worst, true, episodeNotified));
                groupFirings++;
            }
            ranked.add(new Ranked(g, groupFirings, extreme, pts));
        }
        firings.sort(Comparator.comparing(XpFiring::firedAt).thenComparing(f -> f.group().get("group")));
        Comparator<Ranked> closest = Comparator.comparingDouble(r -> Double.isNaN(r.extreme()) ? Double.NEGATIVE_INFINITY : (high ? r.extreme() : -r.extreme()));
        ranked.sort(Comparator.comparingInt(Ranked::firings).reversed().thenComparing(closest.reversed()).thenComparing(Ranked::group));
        List<XpAlertPreview.Series> series = ranked.stream().filter(r -> !r.points().isEmpty()).limit(Math.max(0, maxSeries))
                .map(r -> new XpAlertPreview.Series(r.group(), r.points())).toList();
        return new XpAlertPreview(firings, evaluated, notified, Instant.ofEpochSecond(fromMin + 60), Instant.ofEpochSecond(lastMin + 60),
                cfg.op(), cfg.threshold(), groups.size(), resolved, suppressed, quiet, series, null);
    }

    private record Ranked(String group, int firings, double extreme, List<double[]> points) {}

    public static boolean compare(double v, String op, double t) {
        return switch (op) {
            case ">" -> v > t;
            case ">=" -> v >= t;
            case "<" -> v < t;
            case "<=" -> v <= t;
            default -> throw new IllegalArgumentException("Unsupported operator");
        };
    }
}
