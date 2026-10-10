package com.clinicops.app.web;

import com.clinicops.app.service.NotFoundException;
import com.clinicops.app.tenant.CrossTenantAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class GlobalExceptionHandler {

    /** Cross-tenant access attempts are surfaced as 404, never 403 — don't leak existence. */
    @ExceptionHandler(CrossTenantAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String onCrossTenant() {
        return "error/404";
    }

    /** A record id that doesn't exist (stale link, typo'd URL) — same page as cross-tenant. */
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String onNotFound() {
        return "error/404";
    }
}
