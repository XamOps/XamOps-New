package com.xammer.cloud.service.gcp;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.*;
import com.google.protobuf.util.Timestamps;
import com.xammer.cloud.dto.gcp.GcpMetricDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.monitoring.v3.Aggregation.*;

@Service
@Slf4j
public class GcpMetricsService {

    private final GcpClientProvider gcpClientProvider;

    public GcpMetricsService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public List<GcpMetricDto> getCpuUtilization(String gcpProjectId, String instanceId) throws IOException {
        String metricType = "compute.googleapis.com/instance/cpu/utilization";
        String resourceFilter = String.format("resource.labels.instance_id = \"%s\"", instanceId);
        // CPU Utilization is a GAUGE, usually aligned with MEAN over a period.
        Aggregation.Aligner aligner = Aggregation.Aligner.ALIGN_MEAN;
        Aggregation.Reducer reducer = Aggregation.Reducer.REDUCE_NONE; // No cross-series needed for single instance usually
        boolean asPercentage = true; // Convert decimal (0.0-1.0) to percentage

        return getTimeSeriesData(gcpProjectId, metricType, resourceFilter, aligner, reducer, 24, 3600, asPercentage);
    }

    public List<GcpMetricDto> getCloudSqlConnections(String gcpProjectId, String instanceName) throws IOException {
        String metricType = "cloudsql.googleapis.com/database/network/connections";
        // Cloud SQL instance IDs are typically project_id:instance_name
        String resourceFilter = String.format("resource.labels.database_id = \"%s:%s\"", gcpProjectId, instanceName);
        // Connections is a GAUGE, align with MEAN or MAX depending on need. Using MEAN here.
        Aggregation.Aligner aligner = Aggregation.Aligner.ALIGN_MEAN;
        Aggregation.Reducer reducer = Aggregation.Reducer.REDUCE_NONE;
        boolean asPercentage = false;

        return getTimeSeriesData(gcpProjectId, metricType, resourceFilter, aligner, reducer, 24, 3600, asPercentage);
    }

    /**
     * Fetches the request count for a GCP Load Balancer Forwarding Rule.
     * Note: Assumes an HTTP(S) Load Balancer. Adjust metricType and labels for other LB types.
     *
     * @param gcpProjectId Project ID
     * @param forwardingRuleName The name of the forwarding rule associated with the load balancer backend.
     * @param lookbackHours Hours of data to retrieve.
     * @param periodSeconds Aggregation period in seconds (e.g., 3600 for hourly).
     * @return List of metric data points.
     * @throws IOException If client creation fails.
     */
    public List<GcpMetricDto> getLoadBalancerRequestCount(String gcpProjectId, String forwardingRuleName, int lookbackHours, int periodSeconds) throws IOException {
        // Common metric for HTTP(S) LB request count. Other LBs use different metrics.
        String metricType = "loadbalancing.googleapis.com/https/request_count";
        // Filter by the forwarding rule name
        String resourceFilter = String.format("resource.labels.forwarding_rule_name = \"%s\"", forwardingRuleName);
        // Request count is DELTA, align with SUM to get total count per period.
        Aggregation.Aligner aligner = Aggregation.Aligner.ALIGN_SUM;
        Aggregation.Reducer reducer = Aggregation.Reducer.REDUCE_SUM; // Sum across potential multiple backend services/targets if needed
        boolean asPercentage = false;

        return getTimeSeriesData(gcpProjectId, metricType, resourceFilter, aligner, reducer, lookbackHours, periodSeconds, asPercentage);
    }

    /**
     * Fetches Disk Read IOPS for a Persistent Disk.
     *
     * @param gcpProjectId Project ID
     * @param diskName The name of the persistent disk.
     * @param zone The zone the disk resides in (e.g., "us-central1-a").
     * @param lookbackHours Hours of data to retrieve.
     * @param periodSeconds Aggregation period in seconds (e.g., 300 for 5-min).
     * @return List of metric data points.
     * @throws IOException If client creation fails.
     */
    public List<GcpMetricDto> getDiskReadIops(String gcpProjectId, String diskName, String zone, int lookbackHours, int periodSeconds) throws IOException {
        String metricType = "compute.googleapis.com/disk/read_ops_count";
        // Disk metrics require filtering by disk name AND zone. Disk ID can also be used.
        String resourceFilter = String.format("resource.labels.disk_name = \"%s\" AND resource.labels.zone = \"%s\"", diskName, zone);
        // IOPS count is DELTA, align with SUM and divide by period for rate (ops/sec), or use ALIGN_RATE directly. ALIGN_RATE is simpler.
        Aggregation.Aligner aligner = Aggregation.Aligner.ALIGN_RATE;
        Aggregation.Reducer reducer = Aggregation.Reducer.REDUCE_NONE; // Typically per-disk
        boolean asPercentage = false;

        return getTimeSeriesData(gcpProjectId, metricType, resourceFilter, aligner, reducer, lookbackHours, periodSeconds, asPercentage);
    }

    /**
     * Fetches Disk Write IOPS for a Persistent Disk.
     *
     * @param gcpProjectId Project ID
     * @param diskName The name of the persistent disk.
     * @param zone The zone the disk resides in (e.g., "us-central1-a").
     * @param lookbackHours Hours of data to retrieve.
     * @param periodSeconds Aggregation period in seconds (e.g., 300 for 5-min).
     * @return List of metric data points.
     * @throws IOException If client creation fails.
     */
    public List<GcpMetricDto> getDiskWriteIops(String gcpProjectId, String diskName, String zone, int lookbackHours, int periodSeconds) throws IOException {
        String metricType = "compute.googleapis.com/disk/write_ops_count";
        String resourceFilter = String.format("resource.labels.disk_name = \"%s\" AND resource.labels.zone = \"%s\"", diskName, zone);
        Aggregation.Aligner aligner = Aggregation.Aligner.ALIGN_RATE; // Get ops/sec
        Aggregation.Reducer reducer = Aggregation.Reducer.REDUCE_NONE;
        boolean asPercentage = false;

        return getTimeSeriesData(gcpProjectId, metricType, resourceFilter, aligner, reducer, lookbackHours, periodSeconds, asPercentage);
    }

    /**
     * Generic method to fetch time series data from Cloud Monitoring.
     *
     * @param gcpProjectId Project ID.
     * @param metricType The full metric type (e.g., "compute.googleapis.com/instance/cpu/utilization").
     * @param resourceFilter Filter string based on resource labels (e.g., "resource.labels.instance_id = \"123\"").
     * @param aligner Aggregation aligner (e.g., ALIGN_MEAN, ALIGN_SUM, ALIGN_RATE).
     * @param reducer Aggregation reducer (e.g., REDUCE_SUM, REDUCE_NONE). Use REDUCE_NONE if not grouping across series.
     * @param lookbackHours How many hours back to fetch data.
     * @param periodSeconds The alignment period in seconds.
     * @param asPercentage If true, multiply metric value by 100.
     * @return List of GcpMetricDto.
     * @throws IOException If client creation fails.
     */
    private List<GcpMetricDto> getTimeSeriesData(
            String gcpProjectId,
            String metricType,
            String resourceFilter,
            Aggregation.Aligner aligner,
            Aggregation.Reducer reducer,
            int lookbackHours,
            int periodSeconds,
            boolean asPercentage) throws IOException {

        Optional<MetricServiceClient> clientOpt = gcpClientProvider.getMetricServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();

        List<GcpMetricDto> metrics = new ArrayList<>();
        try (MetricServiceClient metricServiceClient = clientOpt.get()) {
            long startMillis = System.currentTimeMillis() - ((60 * 60 * 1000) * lookbackHours);
            TimeInterval interval = TimeInterval.newBuilder()
                    .setStartTime(Timestamps.fromMillis(startMillis))
                    .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                    .build();

            String filter = String.format("metric.type = \"%s\" AND %s", metricType, resourceFilter);

            ListTimeSeriesRequest.Builder requestBuilder = ListTimeSeriesRequest.newBuilder()
                    .setName("projects/" + gcpProjectId)
                    .setFilter(filter)
                    .setInterval(interval)
                    .setView(ListTimeSeriesRequest.TimeSeriesView.FULL);

            // Add aggregation if needed
            if (aligner != null) {
                Aggregation.Builder aggBuilder = Aggregation.newBuilder()
                        .setAlignmentPeriod(com.google.protobuf.Duration.newBuilder().setSeconds(periodSeconds))
                        .setPerSeriesAligner(aligner);

                if (reducer != null) {
                    aggBuilder.setCrossSeriesReducer(reducer);
                }

                requestBuilder.setAggregation(aggBuilder);
            }

            MetricServiceClient.ListTimeSeriesPagedResponse response = metricServiceClient.listTimeSeries(requestBuilder.build());

            for (TimeSeries ts : response.iterateAll()) {
                for (Point point : ts.getPointsList()) {
                    double value;
                    if (point.getValue().hasDoubleValue()) {
                        value = point.getValue().getDoubleValue();
                    } else if (point.getValue().hasInt64Value()) {
                        value = point.getValue().getInt64Value();
                    } else {
                        continue; // Skip points without numeric values
                    }

                    if (asPercentage) {
                        value *= 100;
                    }

                    metrics.add(new GcpMetricDto(
                            Timestamps.toString(point.getInterval().getStartTime()),
                            value
                    ));
                }
            }
            return metrics;
        } catch (ApiException e) {
            log.error("Failed to get metrics for {} in project {}: {}", metricType, gcpProjectId, e.getStatusCode());
            return List.of();
        }
    }
}