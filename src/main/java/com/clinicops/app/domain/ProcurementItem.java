package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "procurement_item")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcurementItem implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "medicine_id")
    private Long medicineId;

    @Column(name = "medicine_name", nullable = false)
    private String medicineName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "source_visit_id")
    private Long sourceVisitId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String notes;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (source == null) source = Source.MANUAL;
        if (status == null) status = Status.PENDING;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum Source { PRESCRIPTION, MANUAL, LOW_STOCK }
    public enum Status { PENDING, ORDERED, RECEIVED, CANCELLED }
}
