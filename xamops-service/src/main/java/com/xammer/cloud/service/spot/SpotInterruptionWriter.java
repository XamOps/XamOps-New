package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.repository.K8sSpotInterruptionEventRepository;
import com.xammer.cloud.service.spot.SpotObservation.SpotKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The only writer of {@code k8s_spot_interruption_event} (design §B1, §B4.2).
 *
 * <p>Every write is its own {@code REQUIRES_NEW} transaction: locked find → merge or create →
 * fill still-unknown workloads → derive {@code recovery_status} → {@code saveAndFlush}. It runs
 * from an after-commit listener, where the ingest transaction's resources are still bound, so a
 * plain {@code REQUIRED} write would silently never commit. A concurrent insert of the same key
 * raises {@link DataIntegrityViolationException} on the unique constraint; that is retried once
 * in a fresh transaction, which then finds the row. Works on H2 and Postgres alike, unlike
 * native {@code ON CONFLICT}.
 */
@Component
public class SpotInterruptionWriter {

    private static final Logger log = LoggerFactory.getLogger(SpotInterruptionWriter.class);

    private final K8sSpotInterruptionEventRepository repository;
    private final SpotInterruptionProperties properties;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public SpotInterruptionWriter(K8sSpotInterruptionEventRepository repository,
                                  PlatformTransactionManager transactionManager,
                                  SpotInterruptionProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    /**
     * Creates the row for {@code obs.key()} or merges {@code obs} into it.
     *
     * @param provider asked only when the merged row's workloads are still unknown and the row is
     *                 inside the recovery window; may be null
     * @param now      the tracker's clock instant (recovery-window check)
     */
    public K8sSpotInterruptionEvent upsert(SpotObservation obs, WorkloadProvider provider, Instant now) {
        try {
            return writeTx.execute(s -> upsertOnce(obs, provider, now));
        } catch (DataIntegrityViolationException race) {
            log.debug("spot-interruption row for cluster config {} was inserted concurrently; retrying once",
                    obs.key().clusterConfigId());
            return writeTx.execute(s -> upsertOnce(obs, provider, now));
        }
    }

    private K8sSpotInterruptionEvent upsertOnce(SpotObservation obs, WorkloadProvider provider, Instant now) {
        SpotKey key = obs.key();
        K8sSpotInterruptionEvent row = repository
                .findByClusterConfigIdAndNodeNameAndNodeCreatedAt(key.clusterConfigId(), key.nodeName(), key.nodeCreatedAt())
                .map(existing -> {
                    SpotInterruptionMerge.merge(existing, obs);
                    return existing;
                })
                .orElseGet(() -> SpotInterruptionMerge.create(obs));
        return finish(row, provider, now);
    }

    /**
     * Applies {@code mutator} to the existing row for {@code key} under its lock; no row → empty,
     * nothing written.
     */
    public Optional<K8sSpotInterruptionEvent> update(SpotKey key, Consumer<K8sSpotInterruptionEvent> mutator,
                                                     WorkloadProvider provider, Instant now) {
        return writeTx.execute(s -> repository
                .findByClusterConfigIdAndNodeNameAndNodeCreatedAt(key.clusterConfigId(), key.nodeName(), key.nodeCreatedAt())
                .map(row -> {
                    mutator.accept(row);
                    return finish(row, provider, now);
                }));
    }

    private K8sSpotInterruptionEvent finish(K8sSpotInterruptionEvent row, WorkloadProvider provider, Instant now) {
        SpotInterruptionMerge.fillWorkloadsIfUnknown(row, provider, now, properties.recoveryWindow());
        row.setRecoveryStatus(SpotInterruptionMerge.deriveRecoveryStatus(row));
        return repository.saveAndFlush(row);
    }

    /** A read in its own read-only transaction (safe from an after-commit listener). */
    public <T> T read(Supplier<T> work) {
        return readTx.execute(s -> work.get());
    }

    /** Any other write (e.g. an agent_events insert) in its own {@code REQUIRES_NEW} transaction. */
    public void inNewTransaction(Runnable work) {
        writeTx.executeWithoutResult(s -> work.run());
    }
}
