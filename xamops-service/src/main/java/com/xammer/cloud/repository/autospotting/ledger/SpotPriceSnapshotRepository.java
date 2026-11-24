package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SpotPriceSnapshot;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Insert-only access to {@link SpotPriceSnapshot} (design-ledger.md §2.4): save, find by id, exists. */
@Repository
public interface SpotPriceSnapshotRepository extends AppendOnlyRepository<SpotPriceSnapshot, UUID> {}
