package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "visit")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Visit implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "encounter_type", nullable = false)
    private String encounterType;

    @Column(name = "encounter_no", unique = true)
    private String encounterNo;

    // --- Vitals (all optional; captured during the visit) ---
    @Column(name = "vital_bp_systolic")
    private Integer vitalBpSystolic;

    @Column(name = "vital_bp_diastolic")
    private Integer vitalBpDiastolic;

    @Column(name = "vital_pulse")
    private Integer vitalPulse;

    @Column(name = "vital_temperature_f")
    private java.math.BigDecimal vitalTemperatureF;

    @Column(name = "vital_respiratory_rate")
    private Integer vitalRespiratoryRate;

    @Column(name = "vital_spo2")
    private Integer vitalSpo2;

    @Column(name = "vital_weight_kg")
    private java.math.BigDecimal vitalWeightKg;

    @Column(name = "vital_height_cm")
    private java.math.BigDecimal vitalHeightCm;

    @Column(name = "vital_blood_glucose")
    private Integer vitalBloodGlucose;

    @Column(name = "vital_blood_glucose_type")
    private String vitalBloodGlucoseType;

    @Transient
    public java.math.BigDecimal getVitalBmi() {
        if (vitalWeightKg == null || vitalHeightCm == null) return null;
        if (vitalHeightCm.signum() <= 0) return null;
        java.math.BigDecimal heightM = vitalHeightCm.divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal h2 = heightM.multiply(heightM);
        if (h2.signum() <= 0) return null;
        return vitalWeightKg.divide(h2, 1, java.math.RoundingMode.HALF_UP);
    }

    @Column(name = "visit_date", nullable = false)
    private Instant visitDate;

    @Column(length = 1024)
    private String diagnosis;

    @Column(length = 2048)
    private String notes;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "prescription_file")
    private String prescriptionFile;

    @Column(name = "prescription_text", length = 4096)
    private String prescriptionText;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (visitDate == null) visitDate = now;
        if (encounterType == null) encounterType = "OPD";
    }
}
