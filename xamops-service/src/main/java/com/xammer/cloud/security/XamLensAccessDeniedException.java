package com.xammer.cloud.security;

/**
 * The caller may not open XamLens: {@link XamLensTabGuard} refused the {@code secops-xamlens} tab.
 *
 * <p>Extends {@link RuntimeException} rather than Spring Security's {@code AccessDeniedException}, so
 * only {@code MattersExceptionHandler} maps it, to {@code 403 {"code":"TAB_ACCESS_DENIED"}}, and the
 * application-wide {@code AccessDeniedException} handler can never claim it.
 */
public class XamLensAccessDeniedException extends RuntimeException {

    /** The customer-facing message, also the body's {@code message}. */
    public static final String MESSAGE = "You do not have access to XamLens.";

    public XamLensAccessDeniedException() {
        super(MESSAGE);
    }
}
