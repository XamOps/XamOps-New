package com.xammer.cloud.service.spot;

import java.time.Instant;

/**
 * Published by {@code AgentIngestService.processBatch} after a batch of one of the collectors
 * spot-interruption tracking reads was saved to {@code agent_data_cache} (design §B4.1). The
 * listener runs after the ingest transaction commits.
 *
 * @param clusterConfigId {@code kubernetes_cluster_config.id} the batch was stored for
 * @param collector       {@code node-optimizer-report}, {@code k8s-resources} or {@code autoscaling-actions}
 * @param dataJson        the JSON just stored
 * @param prevJson        the JSON it replaced; only set for {@code k8s-resources}, else null
 * @param prevReceivedAt  server receive time of {@code prevJson} as an Instant (via
 *                        {@link ServerTime#serverInstant}); null when there was none
 */
public record AgentBatchStoredEvent(Long clusterConfigId,
                                    String collector,
                                    String dataJson,
                                    String prevJson,
                                    Instant prevReceivedAt) {
}
