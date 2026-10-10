package com.clinicops.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * Master entry for an orderable investigation (e.g. CBC, ECG, CT Brain).
 * Drives the chip-quick-pick on the visit form. Distinct from {@link InvestigationItem}
 * which is a per-visit ordered row.
 */
/** See {@link MedicalDegree} — same platform/clinic-scope semantics. */
@Entity
@Table(name = "medical_investigation")
@Filter(name = "tenantFilter", condition = "(clinic_id IS NULL OR clinic_id = :tenantId)")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicalInvestigation implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = platform-wide. */
    @Column(name = "clinic_id")
    private Long clinicId;

    @NotBlank
    @Column(nullable = false, length = 128)
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

    @Override
    public boolean nullClinicMeansPlatformShared() { return true; }
}
