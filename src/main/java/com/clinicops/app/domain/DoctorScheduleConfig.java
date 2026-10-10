package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

/**
 * Per-doctor appointment scheduling settings.
 *
 * <p>{@code bookingMode}:
 * <ul>
 *   <li>{@code SLOT_BASED} — patients book specific time slots; overbooking controlled by
 *       {@code maxPerSlot}.</li>
 *   <li>{@code TOKEN_ONLY} — sequential token queue; no fixed time slot is assigned.</li>
 * </ul>
 */
@Entity
@Table(name = "doctor_schedule_config")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorScheduleConfig implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** SLOT_BASED | TOKEN_ONLY */
    @Builder.Default
    @Column(name = "booking_mode", nullable = false, length = 20)
    private String bookingMode = "SLOT_BASED";

    @Builder.Default
    @Column(name = "slot_duration_minutes", nullable = false)
    private Integer slotDurationMinutes = 15;

    /** Number of patients allowed per time slot. */
    @Builder.Default
    @Column(name = "max_per_slot", nullable = false)
    private Integer maxPerSlot = 1;

    /** Total bookings cap per day; null = no hard cap. */
    @Column(name = "max_per_day")
    private Integer maxPerDay;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (bookingMode == null) bookingMode = "SLOT_BASED";
        if (slotDurationMinutes == null) slotDurationMinutes = 15;
        if (maxPerSlot == null) maxPerSlot = 1;
    }

    @PreUpdate
    public void onUpdate() { updatedAt = Instant.now(); }

    @Transient
    public boolean isTokenOnly() { return "TOKEN_ONLY".equals(bookingMode); }
}
