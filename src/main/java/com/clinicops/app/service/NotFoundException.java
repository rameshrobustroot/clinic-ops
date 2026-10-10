package com.clinicops.app.service;

/**
 * A requested record doesn't exist. Rendered as a 404 by {@code GlobalExceptionHandler}.
 *
 * <p>Extends {@link IllegalArgumentException} so existing callers that catch IAE (form flows that
 * show the message inline) keep working unchanged.
 */
public class NotFoundException extends IllegalArgumentException {
    public NotFoundException(String message) {
        super(message);
    }
}
