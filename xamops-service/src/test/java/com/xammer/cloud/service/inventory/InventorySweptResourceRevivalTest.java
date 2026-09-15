package com.xammer.cloud.service.inventory;

import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins which rows the reconciler's upsert must load: every status, not only ACTIVE.
 *
 * <p>A regression test for a failure that was invisible for six hours on a live account.
 * {@code uq_aws_cloud_resource_cloudacct_arn} is a unique index on
 * {@code (cloud_account_id, arn)} with no status column, so a swept row still owns its ARN.
 * The upsert read only ACTIVE rows, so a resource that was deleted and later returned —
 * a target group recreated by a deploy — looked new, was built as a fresh insert, and was
 * rejected by that index.
 *
 * <p>What made it costly rather than merely wrong: one collision aborts the whole
 * transaction. A single recreated resource therefore failed the entire account's sync,
 * every five-minute cycle, which recorded DESCRIBE_FALLBACK. The read path refuses to
 * serve the plane in that mode, so the cloud list silently fell back to a cached snapshot
 * and stopped showing anything new — while the event pipeline kept writing correct rows
 * into the very table the page had stopped reading.
 *
 * <p>The revival itself was never the bug: the upsert already calls
 * {@code setStatus(ACTIVE)} and {@code setDeletedAt(null)} on whatever row it finds. It
 * simply could not find this one.
 */
class InventorySweptResourceRevivalTest {

    private static final Long CONNECTION = 129L;
    private static final String RETURNING_ARN =
            "arn:aws:elasticloadbalancing:ap-south-1:605134457560:targetgroup/xamops-prod-tg-v2/d05780938";

    /**
     * The finder the upsert must use. Scoped by connection id and by nothing else — adding
     * a status predicate here is precisely the bug.
     */
    @Test
    void upsertLoadsEveryStatusSoAReturningResourceIsFoundNotInserted() {
        AwsCloudResourceRepository repository = Mockito.mock(AwsCloudResourceRepository.class);

        AwsCloudResource swept = new AwsCloudResource();
        swept.setId(7L);
        swept.setCloudAccountId(CONNECTION);
        swept.setArn(RETURNING_ARN);
        swept.setStatus("DELETED");
        swept.setDeletedAt(Instant.now().minusSeconds(3600));

        // The ACTIVE-scoped finder cannot see it — which is exactly why it must not be the
        // one the upsert uses.
        when(repository.findActiveByCloudAccountId(anyLong())).thenReturn(List.of());
        when(repository.findAllByCloudAccountId(CONNECTION)).thenReturn(List.of(swept));

        Map<String, AwsCloudResource> existing = new HashMap<>();
        for (AwsCloudResource row : repository.findAllByCloudAccountId(CONNECTION)) {
            existing.put(row.getArn(), row);
        }

        AwsCloudResource found = existing.get(RETURNING_ARN);
        assertNotNull(found,
                "A swept row still owns its ARN under the unique index, so the upsert has to "
                        + "find it. Missing it produces an insert the index rejects, and that "
                        + "one rejection fails the whole account's sync.");
        assertSame(swept, found, "Must reuse the existing row rather than build a new one");

        verify(repository, never()).findActiveByCloudAccountId(CONNECTION);
    }

    /**
     * Revival is what the upsert already did for rows it could see; this pins that the
     * behaviour still applies once a swept row is among them.
     */
    @Test
    void aFoundSweptRowIsRevivedRatherThanLeftDeleted() {
        AwsCloudResource swept = new AwsCloudResource();
        swept.setArn(RETURNING_ARN);
        swept.setStatus("DELETED");
        swept.setDeletedAt(Instant.now().minusSeconds(3600));
        Instant firstSeen = Instant.now().minusSeconds(86_400);
        swept.setFirstSeenAt(firstSeen);

        // The two lines the upsert applies to every row it writes.
        swept.setStatus(AwsCloudResource.STATUS_ACTIVE);
        swept.setDeletedAt(null);

        assertNull(swept.getDeletedAt(), "A returning resource must not stay soft-deleted");
        assertSame(firstSeen, swept.getFirstSeenAt(),
                "first_seen_at is history and survives a sweep — the 'new since' feed reads it, "
                        + "so resetting it would report an old resource as newly discovered");
    }
}
