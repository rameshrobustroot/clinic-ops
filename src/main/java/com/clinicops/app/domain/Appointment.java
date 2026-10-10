package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * A single patient appointment slot.
 *
 * <p>Scope model: always clinic-scoped ({@code clinic_id} is never null).
 *
 * <p>Patient reference: {@code patient_id} links to a registered {@link Patient}.
 * For walk-ins not yet registered, {@code patient_name} + {@code patient_phone}
 * are stored directly. Reception can register them later and backfill the FK.
 *
 * <p>Status flow:
 * <pre>
 *   SCHEDULED → ARRIVED → IN_PROGRESS → DONE
 *                ↓                  ↘ CANCELLED
 *              NO_SHOW
 * </pre>
 */
@Entity
@Table(name = "appointment")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    /** Registered patient — null for walk-ins not yet in the system. */
    @Column(name = "patient_id")
    private Long patientId;

    /** Display name — copied from Patient on booking, or entered for walk-in. */
    @Column(name = "patient_name", length = 128)
    private String patientName;

    @Column(name = "patient_phone", length = 15)
    private String patientPhone;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    /** Specific slot time — null means walk-in / token queue only. */
    @Column(name = "appointment_time")
    private LocalTime appointmentTime;

    @Builder.Default
    @Column(name = "duration_minutes")
    private Integer durationMinutes = 15;

    /**
     * SCHEDULED | ARRIVED | IN_PROGRESS | DONE | CANCELLED | NO_SHOW
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "SCHEDULED";

    /** Sequential token number for the day (per doctor). Auto-assigned on booking. */
    @Column(name = "token_no")
    private Integer tokenNo;

    /** Chief complaint / reason for visit. */
    @Column(length = 256)
    private String reason;

    @Column(length = 512)
    private String notes;

    /** Populated when the consultation begins (Visit is created from this appointment). */
    @Column(name = "visit_id")
    private Long visitId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "SCHEDULED";
        if (durationMinutes == null) durationMinutes = 15;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Convenience: terminal states where no further action is expected. */
    @Transient
    public boolean isTerminal() {
        return "DONE".equals(status) || "CANCELLED".equals(status) || "NO_SHOW".equals(status);
    }

    /** Convenience: patient-facing display name. */
    @Transient
    public String displayName() {
        return patientName != null ? patientName : "Patient #" + patientId;
    }
}
