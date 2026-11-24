package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;
import org.springframework.stereotype.Repository;

/** Insert-only access to {@link SavingsLedgerGoLive} (design-ledger.md §2.4): save, find by id, exists. */
@Repository
public interface SavingsLedgerGoLiveRepository extends AppendOnlyRepository<SavingsLedgerGoLive, String> {
    java.util.List<SavingsLedgerGoLive> findAll();
}
