package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.concurrent.Callable;

/**
 * Translates raw AWS SDK exceptions into {@link ErrorKind} at the provider boundary (design-core.md
 * §9.4). Only the HTTP status and exception type are read: the raw message and error body are never
 * touched, so nothing here can leak a provider error into a log or the UI (FR-SEC-06).
 */
public final class AwsErrors {

    private AwsErrors() {
    }

    /** The {@link ErrorKind} for a thrown failure. */
    public static ErrorKind classify(Throwable t) {
        if (t instanceof AwsReadException r) {
            return r.kind();
        }
        if (t instanceof AwsServiceException e) {
            if (e.isThrottlingException()) {
                return ErrorKind.THROTTLED;
            }
            int status = e.statusCode();
            if (status == 401 || status == 403) {
                return ErrorKind.PERMISSION;
            }
            if (status == 404) {
                return ErrorKind.NOT_FOUND;
            }
            if (status == 429) {
                return ErrorKind.THROTTLED;
            }
            if (status >= 500) {
                return ErrorKind.TRANSIENT;
            }
            return ErrorKind.VALIDATION;
        }
        if (t instanceof ApiCallTimeoutException || t instanceof ApiCallAttemptTimeoutException
                || t instanceof SdkClientException) {
            return ErrorKind.TRANSIENT;
        }
        return ErrorKind.TRANSIENT;
    }

    /** Run {@code call}; a failure becomes an {@link AwsReadException} naming {@code check}. */
    public static <T> T read(String check, Callable<T> call) {
        try {
            return call.call();
        } catch (AwsReadException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsReadException(check, classify(e));
        }
    }
}
