package com.xammer.cloud.service.matters;

/**
 * A request to the XamLens Datastores endpoints that failed validation.
 *
 * <p>Raised by {@link DatastoreListQuery} and {@link DatastoreParams} before any service or vendor
 * call, and answered as {@code 400 {"code":"INVALID_REQUEST","message":…,"parameter":…}}. The message
 * names the parameter and the rule it broke, never the rejected value: a search term or a filter value
 * can be customer content, and a message travels into logs as easily as into the response.
 */
public class DspmBadRequestException extends RuntimeException {

    private final String parameter;

    public DspmBadRequestException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    /** The query parameter or path variable that failed validation. */
    public String parameter() {
        return parameter;
    }
}
