package com.xammer.cloud.xampulse.controller.agent;

import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.agent.AgentConfig;
import com.xammer.cloud.xampulse.dto.agent.EnrollChallengeRequest;
import com.xammer.cloud.xampulse.dto.agent.EnrollChallengeResponse;
import com.xammer.cloud.xampulse.dto.agent.EnrollRequest;
import com.xammer.cloud.xampulse.dto.agent.HeartbeatRequest;
import com.xammer.cloud.xampulse.service.agent.AgentConfigService;
import com.xammer.cloud.xampulse.service.agent.AgentEnrollmentService;
import com.xammer.cloud.xampulse.service.agent.AgentSessionService;
import com.xammer.cloud.xampulse.service.agent.EnrollmentNonceService;
import com.xammer.cloud.xampulse.service.setup.DesiredStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints the host agent calls. They are public in SecurityConfig (covered by /api/v1/agent/**) and protected in
 * the handler: enrolment by the STS identity proof, the rest by the agent access token in {@code X-Xp-Token}.
 * The token is not sent as Authorization because the web application reads that header as a user session.
 * Failures carry no body so a probe learns nothing.
 */
@RestController("xpAgentController")
@RequestMapping("/api/v1/agent/xampulse")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XamPulseAgentController {

    public static final String TOKEN_HEADER = "X-Xp-Token";

    private final EnrollmentNonceService nonces;
    private final AgentEnrollmentService enrollment;
    private final AgentSessionService sessions;
    private final DesiredStateService desiredState;

    public XamPulseAgentController(EnrollmentNonceService nonces, AgentEnrollmentService enrollment, AgentSessionService sessions,
                                   DesiredStateService desiredState) {
        this.nonces = nonces;
        this.enrollment = enrollment;
        this.sessions = sessions;
        this.desiredState = desiredState;
    }

    @PostMapping("/enroll/challenge")
    public ResponseEntity<EnrollChallengeResponse> challenge(@RequestBody EnrollChallengeRequest body) {
        if (body == null || body.region() == null || !body.region().matches("^[a-z]{2}(-[a-z]+)+-\\d$")) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(new EnrollChallengeResponse(nonces.issue(body.region()), (int) EnrollmentNonceService.TTL.toSeconds()));
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@RequestBody EnrollRequest body, HttpServletRequest request) {
        AgentEnrollmentService.Result r = enrollment.enroll(body, ip(request));
        if (r instanceof AgentEnrollmentService.Success s) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(s.body());
        }
        if (r instanceof AgentEnrollmentService.Pending p) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(p.body());
        }
        if (r instanceof AgentEnrollmentService.TooMany) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody com.xammer.cloud.xampulse.dto.agent.RefreshRequest body) {
        AgentSessionService.Refreshed r = sessions.refresh(body == null ? null : body.refreshToken());
        if (r.status() != AgentSessionService.RefreshStatus.OK) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r.pair());
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody HeartbeatRequest body) {
        XpAgent agent = sessions.authenticate(token).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.heartbeat(agent, body));
    }

    @GetMapping("/config")
    public ResponseEntity<?> config(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                    @RequestParam(value = "version", defaultValue = "0") long version) {
        XpAgent agent = sessions.authenticate(token).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        AgentConfig cfg = sessions.config(agent);
        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String etag = AgentConfigService.etag(cfg);
        if (version == cfg.configVersion() && version > 0) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noStore()).body(cfg);
    }

    /** Plain text INSTALL or SKIP, no tenant data, never cached. */
    @GetMapping(value = "/desired-state", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> desiredState(@RequestParam(value = "accountId", required = false) String accountId,
                                               @RequestParam(value = "instanceId", required = false) String instanceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_PLAIN)
                .body(desiredState.answer(accountId, instanceId));
    }

    private static String ip(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
