package com.xammer.billops.repository;

import com.xammer.billops.domain.CurSourceView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Read-only access to the CUR registration xamops-service owns. */
public interface CurSourceViewRepository extends JpaRepository<CurSourceView, Long> {

    /**
     * The source for an account, preferring an ACTIVE one.
     *
     * <p>An account can carry more than one row — a report that failed validation and
     * was re-registered leaves the old one behind — and picking arbitrarily would make
     * invoice generation depend on row order. ACTIVE first, then the most recently
     * created, so a FAILED row is still returned when it is all there is: the admin
     * needs to be told the report failed, not that none was ever configured.
     */
    @Query("SELECT s FROM CurSourceView s WHERE s.ownerAccountId = :accountId "
            + "ORDER BY CASE WHEN s.status = 'ACTIVE' THEN 0 ELSE 1 END, s.id DESC")
    java.util.List<CurSourceView> findForAccount(@Param("accountId") String accountId);

    default Optional<CurSourceView> findBestForAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return findForAccount(accountId).stream().findFirst();
    }
}
