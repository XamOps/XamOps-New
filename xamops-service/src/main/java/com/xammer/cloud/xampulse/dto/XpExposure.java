package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Exposure check: what listens on the host (from the agent) against what its security groups let in (from EC2).
 * {@code exposure} per socket: INTERNET (a rule allows 0.0.0.0/0 or ::/0 and the instance has a public IP),
 * PUBLIC_RANGE (a specific public range is allowed), PRIVATE (only private ranges, prefix lists or other groups, or
 * no public IP), BLOCKED (no rule lets it in) or LOCAL (bound to loopback: not reachable from the network).
 * {@code measured} is false when the agent does not report listening sockets; nothing is then called safe.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpExposure(String instanceId, String publicIp, boolean measured, List<String> securityGroups,
                         List<Socket> sockets, List<XpHostLimits.Finding> findings, String note) {

    public record Socket(String protocol, int port, String bind, String address, String service, String exposure, String via) {}
}
