package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SavingsBillFact;
import com.xammer.cloud.domain.autospotting.ledger.SavingsBillFactId;
import org.springframework.stereotype.Repository;

/** Insert-only access to {@link SavingsBillFact} (design-ledger.md §2.4): save, find by id, exists. */
@Repository
public interface SavingsBillFactRepository extends AppendOnlyRepository<SavingsBillFact, SavingsBillFactId> {}
