package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerEntryItem;
import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerEntryItemId;
import org.springframework.stereotype.Repository;

/** Insert-only access to {@link SavingsLedgerEntryItem} (design-ledger.md §2.4): save, find by id, exists. */
@Repository
public interface SavingsLedgerEntryItemRepository extends AppendOnlyRepository<SavingsLedgerEntryItem, SavingsLedgerEntryItemId> {}
