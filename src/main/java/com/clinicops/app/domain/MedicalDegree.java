package com.clinicops.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * Master degree entry. Scope is determined by {@code clinicId}:
 * {@code null} = platform-wide (curated by PLATFORM_ADMIN, visible to every clinic);
 * non-null = clinic-specific addition (visible only to that clinic).
 *
 * <p>The tenant filter is widened to include platform rows so existing queries return
 * the union without explicit fiddling.
 */
@Entity
@Table(name = "medical_degree")
@Filter(name = "tenantFilter", condition = "(clinic_id IS NULL OR clinic_id = :tenantId)")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicalDegree implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = platform-wide. */
    @Column(name = "clinic_id")
    private Long clinicId;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (active == null) active = true;
    }

    /** Null clinic_id = platform-shared row, visible to every clinic. */
    @Override
    public boolean nullClinicMeansPlatformShared() { return true; }
}
