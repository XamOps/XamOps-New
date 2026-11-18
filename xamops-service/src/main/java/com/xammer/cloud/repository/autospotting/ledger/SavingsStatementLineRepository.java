package com.xammer.cloud.repository.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLine;
import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLineId;
import org.springframework.stereotype.Repository;

/** Insert-only access to {@link SavingsStatementLine} (design-ledger.md §2.4): save, find by id, exists. */
@Repository
public interface SavingsStatementLineRepository extends AppendOnlyRepository<SavingsStatementLine, SavingsStatementLineId> {}
