package com.xammer.billops.service.cur;

/**
 * Thrown when invoice data was asked for from the Cost and Usage Report and the
 * report cannot supply it.
 *
 * <p>Carries the reason so the API can hand the admin the cause and the choice of
 * falling back to Cost Explorer, instead of a failure they cannot act on. Deliberately
 * not an error: nothing is broken when an account simply has no report.
 */
public class CurUnavailableException extends RuntimeException {

    private final CurUnavailable reason;
    private final String detail;

    public CurUnavailableException(CurUnavailable reason) {
        this(reason, null);
    }

    /**
     * @param detail what was actually observed — the failed source's status detail, an
     *               Athena error, the month the report starts at. Appended to the
     *               reason's general explanation so the admin sees both what kind of
     *               problem this is and the specifics of their case.
     */
    public CurUnavailableException(CurUnavailable reason, String detail) {
        super(detail == null || detail.isBlank()
                ? reason.message()
                : reason.message() + " (" + detail + ")");
        this.reason = reason;
        this.detail = detail;
    }

    public CurUnavailable getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }
}
