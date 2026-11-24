package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.service.autospotting.core.JobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Purges inbox rows older than 30 days (design-aws.md §9.7): a leased daily job, because the inbox is an
 * idempotency table and not audit. SQS FIFO deduplicates for five minutes and the inbox covers redeliveries and
 * replays beyond that, so 30 days is far longer than the 14-day DLQ retention that bounds a replay.
 */
@Component
public class AwsEventInboxPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AwsEventInboxPurgeJob.class);
    public static final String JOB = "events-inbox-purge";
    static final Duration RETENTION = Duration.ofDays(30);

    private final AwsEventStore store;
    private final JobRunner jobRunner;

    public AwsEventInboxPurgeJob(AwsEventStore store, JobRunner jobRunner) {
        this.store = store;
        this.jobRunner = jobRunner;
    }

    @Scheduled(cron = "${autospotting.aws.events.inbox-purge-cron:0 20 3 * * *}", zone = "UTC",
            scheduler = "spotTaskScheduler")
    public void scheduledDaily() {
        jobRunner.runLeased(JOB, LocalDate.now(ZoneOffset.UTC).toString(), this::purge);
    }

    /** Delete the rows past retention; returns how many. */
    public int purge() {
        int deleted = store.purgeInboxBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("AutoSpotting event inbox: purged {} rows older than {} days", deleted, RETENTION.toDays());
        }
        return deleted;
    }
}
