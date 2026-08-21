package com.xammer.cloud.service;

/**
 * The Redis contract for the rolling filesystem-utilization high-water marks reported by the disk
 * agent.
 *
 * <p>The agent pushes a mount list every five minutes and each push overwrites the previous one, so
 * the rightsizing services only ever saw the most recent instantaneous reading. That is not enough
 * to size a shrink: a filesystem at 30% when the agent happened to look may have been at 90% an
 * hour earlier during log rotation, a build, or a nightly import. Shrinking to fit the sample then
 * fills the disk, and neither GCP nor Azure can grow a disk back without a migration.
 *
 * <p>The ingestion path merges each push into a per-device maximum under {@link #keyFor}, and the
 * rightsizing services size shrinks against that instead.
 *
 * <p>Shared here rather than on the controller so the services do not have to depend on the web
 * layer to read a key the web layer writes.
 */
public final class DiskAgentPeaks {

    private DiskAgentPeaks() {
    }

    /** Prefix applied in front of the provider's own mount-key prefix. */
    public static final String KEY_PREFIX = "disk-agent-peak:";

    /** Map field holding how many pushes have contributed to the peaks. */
    public static final String SAMPLE_COUNT_FIELD = "__samples";

    /** Map field holding when observation started, as epoch milliseconds. */
    public static final String WINDOW_START_FIELD = "__since";

    /**
     * How long the peak record lives. Longer than the six-hour mount TTL on purpose: the point is
     * to accumulate the history a single snapshot cannot provide. At one push every five minutes a
     * full window holds roughly 4,000 observations.
     */
    public static final int TTL_MINUTES = 14 * 24 * 60;

    /**
     * Observations required before a shrink may be recommended.
     *
     * <p>At one push every five minutes, 288 observations is about a day — long enough to have seen
     * at least one daily log rotation or nightly batch window.
     */
    public static final int MIN_OBSERVATIONS_FOR_SHRINK = 288;

    /**
     * The key holding one instance's high-water marks.
     *
     * @param mountKeyPrefix the provider's mount-key prefix, e.g. {@code gcp-disk-agent:}, so the
     *                       three providers cannot collide
     */
    public static String keyFor(String mountKeyPrefix, String cloudAccountId, String instanceId) {
        return KEY_PREFIX + mountKeyPrefix + cloudAccountId + ":" + instanceId;
    }

    /**
     * Merges one push's mount readings into an instance's existing high-water marks.
     *
     * <p>A device's stored value only ever rises. The sample count rises by one per push that
     * carried at least one usable reading, so it counts observations rather than devices — a
     * two-disk instance must not reach the shrink threshold in half the time.
     *
     * @param existing the stored map, or null on the first push
     * @param mounts   this push's mount entries; entries without a device or a numeric
     *                 {@code usedPercent} are ignored
     * @param nowMillis clock reading used for the window start on the first push
     * @return a new map to store, or empty when the push carried nothing usable
     */
    public static java.util.Optional<java.util.Map<String, Object>> merge(
            java.util.Map<String, Object> existing,
            java.util.List<java.util.Map<String, Object>> mounts,
            long nowMillis) {

        java.util.Map<String, Object> peaks = existing == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(existing);

        boolean anyUsable = false;
        for (java.util.Map<String, Object> mount : mounts == null ? java.util.List.<java.util.Map<String, Object>>of() : mounts) {
            Object deviceRaw = mount.get("device");
            Object usedRaw = mount.get("usedPercent");
            if (!(deviceRaw instanceof String device) || device.isBlank()
                    || !(usedRaw instanceof Number used)) {
                continue;
            }
            double observed = used.doubleValue();
            if (Double.isNaN(observed) || Double.isInfinite(observed)) {
                continue;
            }
            anyUsable = true;
            Object priorRaw = peaks.get(device);
            double prior = priorRaw instanceof Number p ? p.doubleValue() : Double.NEGATIVE_INFINITY;
            if (observed > prior) {
                peaks.put(device, Math.round(observed * 10.0) / 10.0);
            }
        }

        if (!anyUsable) {
            return java.util.Optional.empty();
        }

        peaks.putIfAbsent(WINDOW_START_FIELD, nowMillis);
        Object countRaw = peaks.get(SAMPLE_COUNT_FIELD);
        long samples = countRaw instanceof Number n ? n.longValue() : 0L;
        peaks.put(SAMPLE_COUNT_FIELD, samples + 1);
        return java.util.Optional.of(peaks);
    }
}
