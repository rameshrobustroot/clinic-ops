/**
 * Domain entities. The {@code tenantFilter} below is auto-applied to every
 * read query for clinic-scoped entities (see each entity's {@code @Filter}).
 * Activation: see {@link com.clinicops.app.tenant.TenantFilterEnabler}.
 */
@org.hibernate.annotations.FilterDef(
        name = "tenantFilter",
        parameters = @org.hibernate.annotations.ParamDef(name = "tenantId", type = Long.class))
package com.clinicops.app.domain;
