package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import com.xammer.cloud.xampulse.service.stream.InMemoryTicketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Command;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SendCommandResponse;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunbookServiceTest {

    private static final long MINE = 100L;
    private static final String CMD = "0b2c3d4e-1111-2222-3333-444455556666";
    private final XamPulseProperties props = new XamPulseProperties();
    private final SsmClient ssm = mock(SsmClient.class);
    private final AuditService audit = mock(AuditService.class);
    private final ClientUserDetails user = new ClientUserDetails("u", "x", List.of(), 1L, 10L, "t");
    private RunbookService svc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CloudAccountAccessService access = mock(CloudAccountAccessService.class);
        CloudAccount mine = new CloudAccount();
        mine.setId(MINE);
        mine.setAwsAccountId("123456789012");
        mine.setProvider("AWS");
        when(access.requireAccess(any(), eq(MINE))).thenReturn(mine);
        XamPulseAccessGuard guard = new XamPulseAccessGuard(access, mock(CloudAccountRepository.class));
        XpAgentRepository agents = mock(XpAgentRepository.class);
        XpAgent a = new XpAgent();
        a.setCloudAccountId(MINE);
        a.setInstanceId("i-0abc12345");
        a.setRegion("us-east-1");
        when(agents.findByCloudAccountIdAndInstanceId(MINE, "i-0abc12345")).thenReturn(Optional.of(a));
        AwsClientProvider aws = mock(AwsClientProvider.class);
        when(aws.getSsmClient(any(), eq("us-east-1"))).thenReturn(ssm);
        when(ssm.sendCommand(any(Consumer.class))).thenAnswer(inv -> {
            SendCommandRequest.Builder b = SendCommandRequest.builder();
            ((Consumer<SendCommandRequest.Builder>) inv.getArgument(0)).accept(b);
            lastSent = b.build();
            return SendCommandResponse.builder().command(Command.builder().commandId(CMD).build()).build();
        });
        svc = new RunbookService(props, guard, agents, aws, new InMemoryTicketStore(), audit);
    }

    private SendCommandRequest lastSent;

    @Test
    void offByDefaultNothingIsSent() {
        XamPulseException e = assertThrows(XamPulseException.class, () -> svc.run(user, MINE, "i-0abc12345", "top", "1.2.3.4"));
        assertEquals(403, e.getHttpStatus());
        verifyNoInteractions(ssm, audit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsOnlyTheFixedScriptOncePerHostAuditsAndBindsTheOutputToItsAccountAndHost() {
        props.getRemoteActions().setEnabled(true);
        RunbookService.Started s = svc.run(user, MINE, "i-0abc12345", "top", "1.2.3.4");
        assertEquals(CMD, s.commandId());
        assertEquals("AWS-RunShellScript", lastSent.documentName());
        assertEquals(List.of(RunbookService.CATALOG.get(0).script()), lastSent.parameters().get("commands"), "exactly the catalogue script");
        verify(audit).record(any(), eq(MINE), eq("runbook.run"), eq("instance=i-0abc12345,runbook=top,command=" + CMD), eq("1.2.3.4"));
        assertEquals(409, assertThrows(XamPulseException.class, () -> svc.run(user, MINE, "i-0abc12345", "disk", "1.2.3.4")).getHttpStatus(),
                "one runbook at a time per host");
        assertEquals(404, assertThrows(XamPulseException.class, () -> svc.run(user, MINE, "i-0abc12345", "rm -rf /", "1.2.3.4")).getHttpStatus(),
                "anything not in the catalogue does not exist");
        assertEquals(404, assertThrows(XamPulseException.class, () -> svc.run(user, MINE, "i-0fff99999", "top", "1.2.3.4")).getHttpStatus(),
                "a host this account did not enrol is not a target");

        when(ssm.getCommandInvocation(any(Consumer.class))).thenReturn(GetCommandInvocationResponse.builder().status("Success")
                .standardOutputContent("PID USER\n\u001B[31m1 root\u001B[0m\u0007\n").standardErrorContent("").build());
        RunbookService.Result r = svc.result(user, MINE, "i-0abc12345", CMD);
        assertEquals("SUCCESS", r.status());
        assertEquals("PID USER\n1 root\n", r.output(), "terminal escapes and control characters removed");
        assertEquals(404, assertThrows(XamPulseException.class, () -> svc.result(user, MINE, "i-0abc12345", "00000000-1111-2222-3333-444455556666")).getHttpStatus(),
                "a command not started here cannot be read");
        // finished: the host is free again
        assertEquals(CMD, svc.run(user, MINE, "i-0abc12345", "disk", "1.2.3.4").commandId());
    }

    @Test
    void theCatalogueIsReadOnly() {
        for (RunbookService.Runbook r : RunbookService.CATALOG) {
            String s = r.script().replaceAll("'[^']*'", "''"); // quoted text (grep patterns, formats) is not a command
            assertFalse(s.matches("(?s).*(\\brm\\b|\\bkill\\b|\\breboot\\b|\\bshutdown\\b|systemctl (restart|stop|start)|>\\s*/(?!dev/null)|\\bchmod\\b|\\bchown\\b|\\bcurl\\b|\\bwget\\b).*"),
                    r.id() + " must not change the host: " + s);
        }
    }
}
