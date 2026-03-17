package com.xammer.cloud.dto.dbops;

import java.util.List;

/**
 * Per-instance time-series metrics for the DbOps detail drawer.
 * Each metric is a list of {timestamp (epoch ms), value} points.
 */
public class DbInstanceTimeseriesDto {

    private String instanceId;
    private String region;

    private List<Point> cpu;
    private List<Point> connections;
    private List<Point> readIops;
    private List<Point> writeIops;
    private List<Point> readLatencyMs;
    private List<Point> writeLatencyMs;
    private List<Point> freeStorageGb;
    private List<Point> freeableMemoryMb;
    private List<Point> networkReceiveMbps;
    private List<Point> networkTransmitMbps;
    private List<Point> replicaLagSeconds;

    public static class Point {
        private long ts;   // epoch milliseconds
        private double v;  // metric value

        public Point() {}
        public Point(long ts, double v) { this.ts = ts; this.v = v; }

        public long getTs() { return ts; }
        public void setTs(long ts) { this.ts = ts; }
        public double getV() { return v; }
        public void setV(double v) { this.v = v; }
    }

    // Getters and setters
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public List<Point> getCpu() { return cpu; }
    public void setCpu(List<Point> cpu) { this.cpu = cpu; }
    public List<Point> getConnections() { return connections; }
    public void setConnections(List<Point> connections) { this.connections = connections; }
    public List<Point> getReadIops() { return readIops; }
    public void setReadIops(List<Point> readIops) { this.readIops = readIops; }
    public List<Point> getWriteIops() { return writeIops; }
    public void setWriteIops(List<Point> writeIops) { this.writeIops = writeIops; }
    public List<Point> getReadLatencyMs() { return readLatencyMs; }
    public void setReadLatencyMs(List<Point> readLatencyMs) { this.readLatencyMs = readLatencyMs; }
    public List<Point> getWriteLatencyMs() { return writeLatencyMs; }
    public void setWriteLatencyMs(List<Point> writeLatencyMs) { this.writeLatencyMs = writeLatencyMs; }
    public List<Point> getFreeStorageGb() { return freeStorageGb; }
    public void setFreeStorageGb(List<Point> freeStorageGb) { this.freeStorageGb = freeStorageGb; }
    public List<Point> getFreeableMemoryMb() { return freeableMemoryMb; }
    public void setFreeableMemoryMb(List<Point> freeableMemoryMb) { this.freeableMemoryMb = freeableMemoryMb; }
    public List<Point> getNetworkReceiveMbps() { return networkReceiveMbps; }
    public void setNetworkReceiveMbps(List<Point> networkReceiveMbps) { this.networkReceiveMbps = networkReceiveMbps; }
    public List<Point> getNetworkTransmitMbps() { return networkTransmitMbps; }
    public void setNetworkTransmitMbps(List<Point> networkTransmitMbps) { this.networkTransmitMbps = networkTransmitMbps; }
    public List<Point> getReplicaLagSeconds() { return replicaLagSeconds; }
    public void setReplicaLagSeconds(List<Point> replicaLagSeconds) { this.replicaLagSeconds = replicaLagSeconds; }
}
