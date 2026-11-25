package com.xammer.cloud.xampulse.controller;

import jakarta.servlet.http.HttpServletRequest;

/** Small shared helpers for the XamPulse controllers. */
final class XamPulseControllerSupport {

    private XamPulseControllerSupport() {}

    static String ip(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip;
    }

    /**
     * The real admin's id when the current request runs under impersonation, else null. The id comes from the HTTP
     * session marker the impersonation filter sets; impersonation without that marker is refused.
     */
    static Long impersonationActor(HttpServletRequest http) {
        if (com.xammer.cloud.config.multitenancy.ImpersonationContext.getImpersonatedUserId() == null) {
            return null;
        }
        jakarta.servlet.http.HttpSession session = http.getSession(false);
        Object admin = session == null ? null : session.getAttribute("X-ORIGINAL-ADMIN-ID");
        if (!(admin instanceof Number n)) {
            throw com.xammer.cloud.xampulse.exception.XamPulseException.forbidden("This action is not available for this session.");
        }
        return n.longValue();
    }
}
