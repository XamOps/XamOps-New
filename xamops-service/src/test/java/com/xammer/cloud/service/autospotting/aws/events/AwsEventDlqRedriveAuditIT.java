package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.autospotting.pg.OpsContexts;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-23b-04: the redrive runs on the controller path with no transaction, and the audit writer is MANDATORY, so the
 * audit row was never written (only a WARN). With the real writer and a real database the row must exist.
 */
@Tag("containers")
class AwsEventDlqRedriveAuditIT {

    @Test
    void aRedriveWithNoSurroundingTransactionWritesItsAuditRow() {
        try (PgHarness.Database db = PgHarness.emptySchema();
             AnnotationConfigApplicationContext ctx = OpsContexts.contextFor(db)) {
            JdbcTemplate jdbc = (JdbcTemplate) ctx.getBean("beanJdbc");
            FakeSqsClient sqs = new FakeSqsClient();
            AwsEventDlqService service = new AwsEventDlqService(sqs,
                    new AwsEventProperties(false, true, FakeSqsClient.QUEUE_URL, true), new ObjectMapper(),
                    ctx.getBean(AutoSpottingAuditWriter.class), ctx.getBean(PlatformTransactionManager.class));

            AwsEventDlqService.RedriveResult result = service.redrive("replay after the template fix",
                    Actor.operator(100L, "ROLE_ADMIN"));

            assertThat(result.mode()).isEqualTo("MOVE_TASK");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit WHERE action = 'DLQ_REDRIVE'"
                    + " AND outcome = 'SUCCESS'", Integer.class)).as("the redrive audit row").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT details::text FROM autospotting_audit WHERE action = 'DLQ_REDRIVE'",
                    String.class)).contains("replay after the template fix");
        }
    }
}
