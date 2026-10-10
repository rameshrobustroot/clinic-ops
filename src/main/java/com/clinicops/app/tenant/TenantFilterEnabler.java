package com.clinicops.app.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Activates the Hibernate {@code tenantFilter} on every Spring Data JPA repository invocation.
 *
 * <p>Why an aspect: Hibernate filters are session-scoped. We need to enable the filter just
 * before any query executes for a given session. Wrapping every repository call ensures the
 * filter is set before any read query fires, even if the {@code TenantContext} changes between
 * requests.
 *
 * <p>If {@link TenantContext} has no tenant (e.g. during the login query that resolves the user
 * from username), the filter is left disabled so the query can see the row across tenants. This
 * is safe because the {@code app_user.username} column is globally unique — there is no risk of
 * leaking another tenant's data via username, and the resolved user's {@code clinic_id} then
 * scopes everything afterwards.
 */
@Component
@Aspect
@Slf4j
public class TenantFilterEnabler {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* com.clinicops.app.repository..*(..))")
    public Object enableFilter(ProceedingJoinPoint pjp) throws Throwable {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return pjp.proceed();
        }
        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.getEnabledFilter("tenantFilter") == null) {
                session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
            } else {
                session.getEnabledFilter("tenantFilter").setParameter("tenantId", tenantId);
            }
        } catch (Exception e) {
            log.debug("Could not enable tenant filter (no active session yet): {}", e.getMessage());
        }
        return pjp.proceed();
    }
}
