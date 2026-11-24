package com.xammer.cloud.repository.autospotting.ledger;
import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLine;
import com.xammer.cloud.domain.autospotting.ledger.SavingsStatementLineId;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;
/** Insert-only access to {@link SavingsStatementLine} (design-ledger.md §2.4): save, find by id, exists, and a read of the lines that cite an entry. */
@Repository
public interface SavingsStatementLineRepository extends AppendOnlyRepository<SavingsStatementLine, SavingsStatementLineId> {
    /** The lines whose {@code entry_ids} contain the entry, for the recompute tool (AC-LED-17). Read only. */
    @Query(value = "SELECT * FROM savings_statement_line WHERE :entryId = ANY(entry_ids) ORDER BY statement_id, line_no",
            nativeQuery = true)
    List<SavingsStatementLine> findReferencing(@Param("entryId") UUID entryId);
}
