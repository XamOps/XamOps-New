package com.xammer.cloud.service.matters;

import org.springframework.http.HttpStatus;

/**
 * Every failure mode of the Matters.ai integration, as one exception carrying a {@link Reason}.
 *
 * <h2>Why a reason enum rather than an exception per case</h2>
 * The reasons are not interchangeable and the difference is the whole point. "Rotate the key",
 * "buy the add-on", "we have not provisioned your project yet" and "Matters is down" are four
 * different messages to four different audiences, and collapsing them into a generic failure is how
 * an expired credential turns into a support ticket that says "DSPM is broken".
 *
 * The enum keeps that distinction in one readable table instead of a dozen near-identical classes,
 * and each reason pre-declares two things the callers need:
 * <ul>
 *   <li>{@link Reason#status()} — the HTTP status the API should return, so controllers do not each
 *       re-derive it and drift.</li>
 *   <li>{@link Reason#connectionStatus()} — which {@code MattersProjectMapping.STATUS_*} value this
 *       failure implies, or null where the failure says nothing about the credential. A rate limit
 *       or a vendor outage must not mark a perfectly good key as broken, and that rule lives here
 *       rather than in each catch block.</li>
 * </ul>
 */
public class MattersApiException extends RuntimeException {

    public enum Reason {

        /**
         * No mapping row for this client — their Matters project has not been recorded yet.
         *
         * 409 rather than 404: the request is well-formed and the customer may well be entitled,
         * it is the server-side provisioning that is incomplete. The UI renders this as "being set
         * up", which is the honest description of the gap between signing a contract and an
         * operator creating the project.
         */
        NOT_CONFIGURED(HttpStatus.CONFLICT, null),

        /** Mapping exists but an operator has parked it. Says nothing about the key's validity. */
        DISABLED(HttpStatus.CONFLICT, null),

        /**
         * Client does not have the add-on. 403, and deliberately checked before anything else —
         * an unentitled caller should never cause an outbound request, let alone reach a state
         * where the response hints at whether a project exists.
         */
        NOT_ENTITLED(HttpStatus.FORBIDDEN, null),

        /**
         * The stored key is past its recorded expiry. Caught locally, before calling out: Matters
         * caps key lifetime at 180 days, so this is the expected end of every key's life and
         * deserves "rotate the key" rather than a bare 401.
         */
        KEY_EXPIRED(HttpStatus.CONFLICT, com.xammer.cloud.domain.MattersProjectMapping.STATUS_INVALID_KEY),

        /** Malformed, wrong-secret, or revoked key — Matters answered 401. */
        INVALID_KEY(HttpStatus.CONFLICT, com.xammer.cloud.domain.MattersProjectMapping.STATUS_INVALID_KEY),

        /**
         * A credential is on record but would not decrypt — an XamOps fault, not a Matters one.
         *
         * Separated from {@link #INVALID_KEY} because the remedy is the opposite. Nobody should go to
         * Matters and rotate anything; the key is very likely fine and
         * {@code app.encryption.secret-key} does not match the value the row was written with.
         * Detected via the {@code api_key_last_four} sentinel, since
         * {@code EncryptionService.decrypt} returns null rather than throwing and would otherwise be
         * indistinguishable from "no key stored".
         *
         * 500, not 409: nothing the caller sent is wrong, and nothing they can do will fix it.
         */
        DECRYPT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
                com.xammer.cloud.domain.MattersProjectMapping.STATUS_DECRYPT_FAILED),

        /**
         * The stored value does not look like a Matters key at all.
         *
         * Guards the {@link com.xammer.cloud.domain.converter.EncryptedStringConverter} passthrough:
         * it returns values without the {@code enc:v1:} prefix unchanged, as a migration affordance
         * for legacy plaintext. This column has no legacy plaintext, so that path is pure downside —
         * anything written directly into it would be used verbatim. Checking the shape closes it.
         */
        MALFORMED_KEY(HttpStatus.INTERNAL_SERVER_ERROR,
                com.xammer.cloud.domain.MattersProjectMapping.STATUS_INVALID_KEY),

        /**
         * Key was issued for a different Matters environment than {@code base_url} points at.
         *
         * Its own reason because it is the most likely mistake when adding a mapping by hand, and
         * because the fix is the opposite of rotation: the key is fine, the URL is wrong.
         */
        WRONG_ENVIRONMENT(HttpStatus.CONFLICT, com.xammer.cloud.domain.MattersProjectMapping.STATUS_INVALID_KEY),

        /** Key authenticates but lacks the PLATFORM scope the All-Assets endpoints require. */
        FORBIDDEN_SCOPE(HttpStatus.CONFLICT, com.xammer.cloud.domain.MattersProjectMapping.STATUS_FORBIDDEN),

        /**
         * Over the 600 req/min per-key budget, after the client's own bounded retry gave up.
         *
         * Passed straight through as 429 with the upstream {@code Retry-After}, rather than being
         * dressed up as a server error: the caller can act on it, and a polling UI should back off
         * rather than retry immediately. Leaves {@code connection_status} alone — being busy is not
         * being broken.
         */
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, null),

        /** Asset id unknown, not a UUID, or outside the key's project. Matters answered 404. */
        ASSET_NOT_FOUND(HttpStatus.NOT_FOUND, null),

        /**
         * Matters is unreachable or returned 5xx after retries. 502, and no credential downgrade:
         * marking the mapping INVALID_KEY on a vendor outage would leave an operator rotating a key
         * that was never the problem.
         */
        UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, com.xammer.cloud.domain.MattersProjectMapping.STATUS_UNREACHABLE),

        /** Matters answered 2xx with a body we could not parse. A vendor contract change. */
        BAD_UPSTREAM_RESPONSE(HttpStatus.BAD_GATEWAY, null);

        private final HttpStatus status;
        private final String connectionStatus;

        Reason(HttpStatus status, String connectionStatus) {
            this.status = status;
            this.connectionStatus = connectionStatus;
        }

        /** HTTP status the XamOps API should answer with. */
        public HttpStatus status() {
            return status;
        }

        /**
         * The {@code MattersProjectMapping.STATUS_*} this failure implies, or null when the failure
         * carries no verdict on the credential and the stored status must be left untouched.
         */
        public String connectionStatus() {
            return connectionStatus;
        }
    }

    private final Reason reason;

    /**
     * Seconds to wait, from the upstream {@code Retry-After}. Only meaningful for
     * {@link Reason#RATE_LIMITED}; 0 when unknown so callers can treat it as "no guidance".
     */
    private final int retryAfterSeconds;

    public MattersApiException(Reason reason, String message) {
        this(reason, message, 0, null);
    }

    public MattersApiException(Reason reason, String message, Throwable cause) {
        this(reason, message, 0, cause);
    }

    public MattersApiException(Reason reason, String message, int retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public Reason getReason() {
        return reason;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Stable machine-readable code for the frontend to branch on.
     *
     * The UI needs to tell "not provisioned yet" from "key expired" from "not entitled" in order to
     * render three different screens. Branching on the human-readable message would break the moment
     * someone improves the wording, so the enum name is the contract and the message is free text.
     */
    public String code() {
        return reason.name();
    }
}
