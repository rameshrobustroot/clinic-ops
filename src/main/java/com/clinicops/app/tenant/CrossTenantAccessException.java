package com.clinicops.app.tenant;

/**
 * Raised by {@link TenantGuard} or {@link TenantEntityListener} when code
 * attempts to read/write an entity belonging to a different tenant than the
 * current request. The {@code GlobalExceptionHandler} maps this to a 404 so
 * we never leak the existence of cross-tenant rows.
 */
public class CrossTenantAccessException extends RuntimeException {
    public CrossTenantAccessException() {
        super("Entity not found");
    }
    public CrossTenantAccessException(String message) {
        super(message);
    }
}
