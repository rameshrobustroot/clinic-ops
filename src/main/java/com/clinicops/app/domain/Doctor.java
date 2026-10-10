package com.clinicops.app.domain;

import com.clinicops.app.util.ValidationPatterns;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * A consulting doctor at the clinic. Optionally linked to an {@link AppUser}
 * when the doctor also has a login. Most visiting specialists won't.
 */
@Entity
@Table(name = "doctor")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Doctor implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @NotBlank(message = "Name is required")
    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** e.g. "MBBS, MD (General Medicine)" — printed on prescription letterhead. */
    private String qualification;

    /** Medical council registration number (MCI/state). */
    @Column(name = "registration_no")
    private String registrationNo;

    /** e.g. "Internal Medicine", "Cardiology". */
    private String specialization;

    @Pattern(regexp = ValidationPatterns.PHONE_INDIA_OPTIONAL,
             message = "Enter a 10-digit mobile starting with 6/7/8/9")
    private String phone;

    private String email;

    /** Optional FK to AppUser when this doctor also has a clinic login. */
    @Column(name = "user_id")
    private Long userId;

    /** Soft-delete flag. Inactive doctors stay referenceable by old visits but
     *  are hidden from the visit-form dropdown and the doctor list. */
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
}
