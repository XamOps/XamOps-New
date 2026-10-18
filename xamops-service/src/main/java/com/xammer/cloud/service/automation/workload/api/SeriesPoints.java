package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.usage.PrometheusRangeReader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Grid-aligned values to wire points, and the few figures derived from them. Nulls stay null. */
final class SeriesPoints {

    private SeriesPoints() {
    }

    /** One {@code [t, v]} per grid point, {@code v} null where nothing was measured. */
    static List<UsageSeriesDto.Point> points(Double[] values, PrometheusRangeReader.Grid grid) {
        List<UsageSeriesDto.Point> points = new ArrayList<>(grid.points());
        for (int i = 0; i < grid.points(); i++) {
            points.add(new UsageSeriesDto.Point(grid.at(i), i < values.length ? values[i] : null));
        }
        return points;
    }

    /** The first answered series' values, or every point null when Prometheus matched nothing. */
    static Double[] first(List<PrometheusRangeReader.Series> answered, PrometheusRangeReader.Grid grid) {
        return answered.isEmpty() ? new Double[grid.points()] : answered.get(0).values();
    }

    /** The time of the first measured point, or null when there is none. */
    static Instant firstSample(Double[] values, PrometheusRangeReader.Grid grid) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                return Instant.ofEpochSecond(grid.at(i));
            }
        }
        return null;
    }

    static boolean anySample(Double[] values) {
        for (Double value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    /** The mean of the measured points; null when none was measured. */
    static Double mean(Double[] values) {
        double sum = 0;
        int count = 0;
        for (Double value : values) {
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /** The last point's value: "now" on a window that ends now. Null when it was not measured. */
    static Double last(Double[] values) {
        return values.length == 0 ? null : values[values.length - 1];
    }

    /** Four decimals, so a share reads the same on every serialisation. */
    static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
