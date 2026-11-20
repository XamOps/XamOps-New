package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.core.JobRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The daily {@code template-gc:gcp} job (design-gcp.md §9.3, AC-GCP-05, AC-GCP-06): one instance runs it under a job
 * lease, once per UTC day. It only calls {@link GcpTemplateGc}, which deletes registry rows alone (RETIRED, past the
 * grace period, unreferenced by any MIG, matching id) and does nothing while the GCP mutation gate is closed. A
 * template is never chosen by its name.
 */
@Component
public class GcpTemplateGcJob {
    public static final String JOB = "template-gc:gcp";

    private final GcpTemplateGc gc;
    private final JobRunner jobRunner;

    public GcpTemplateGcJob(GcpTemplateGc gc, JobRunner jobRunner) {
        this.gc = gc;
        this.jobRunner = jobRunner;
    }

    @Scheduled(cron = "${autospotting.gcp.template-gc-cron:0 40 3 * * *}", zone = "UTC", scheduler = "spotTaskScheduler")
    public void scheduledDaily() {
        jobRunner.runLeased(JOB, LocalDate.now(ZoneOffset.UTC).toString(), gc::runOnce);
    }
}
