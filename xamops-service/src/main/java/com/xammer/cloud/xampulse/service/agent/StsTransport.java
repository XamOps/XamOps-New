package com.xammer.cloud.xampulse.service.agent;

import java.net.URI;
import java.util.Map;

/** Sends the already-validated, agent-signed request to STS. A seam so the verifier can be tested without a network. */
public interface StsTransport {

    record Response(int status, String body) {}

    /** Must not follow redirects, must time out in seconds and must cap the response size. */
    Response post(URI uri, Map<String, String> headers, String body) throws java.io.IOException;
}
