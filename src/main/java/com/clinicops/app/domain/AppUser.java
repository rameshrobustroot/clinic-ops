package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "app_user",
        // Mirrors Liquibase 008: usernames are unique per clinic, not globally (login is clinicCode/username).
        uniqueConstraints = @UniqueConstraint(name = "uk_appuser_clinic_username", columnNames = {"clinic_id", "username"}))
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null only for {@link Role#PLATFORM_ADMIN} — they don't belong to any clinic. */
    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String phone;

    /** Doctor's qualification, e.g. "MBBS, MD (Medicine)". Appears on prescription PDFs. */
    private String qualification;

    /** Medical council registration number — legally required on Indian prescriptions. */
    @Column(name = "registration_no")
    private String registrationNo;

    /** Specialization, e.g. "General Physician", "Cardiologist". */
    private String specialization;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    /** Color theme preference: violet / sky / emerald. */
    @Builder.Default
    @Column(nullable = false)
    private String theme = "violet";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
