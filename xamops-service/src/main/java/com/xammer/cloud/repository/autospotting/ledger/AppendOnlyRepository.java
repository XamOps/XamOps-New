package com.xammer.cloud.repository.autospotting.ledger;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * The only repository shape the immutable ledger tables get (design-ledger.md §2.4, FR-LED-13): an
 * insert-only {@code save}, id finders and {@code exists}. There is no {@code delete*}, no
 * {@code saveAll} and no {@code @Modifying} query. The entities report {@code isNew()} until they
 * are persisted ({@link com.xammer.cloud.domain.autospotting.ledger.AppendOnlyEntity}), so
 * {@code save} always inserts, and the table's trigger refuses an UPDATE or DELETE anyway.
 *
 * @param <T>  the entity type
 * @param <ID> the primary key type
 */
@NoRepositoryBean
public interface AppendOnlyRepository<T, ID> extends Repository<T, ID> {

    /** Inserts a new row. Saving an entity that is already persisted never updates it (it fails). */
    <S extends T> S save(S entity);

    Optional<T> findById(ID id);

    boolean existsById(ID id);
}
