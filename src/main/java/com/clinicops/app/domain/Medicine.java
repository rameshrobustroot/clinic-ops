package com.clinicops.app.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "medicine")
@Filter(name = "tenantFilter", condition = "(clinic_id IS NULL OR clinic_id = :tenantId)")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Medicine implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = platform medicine (visible to all clinics). Non-null = clinic-private. */
    @Column(name = "clinic_id")
    private Long clinicId;

    @Override
    public boolean nullClinicMeansPlatformShared() {
        return true; // platform catalog medicines (clinic_id = NULL) are readable by everyone
    }

    @NotBlank(message = "Name is required")
    @Column(nullable = false)
    private String name;

    @Column(name = "generic_name")
    private String genericName;

    private String form;
    private String strength;
    private String manufacturer;
    private String unit;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    /** Soft-delete flag. Inactive medicines stay referenceable by old sale/prescription records
     *  but are hidden from inventory, search, and pickers. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (unit == null) unit = "UNIT";
        // Last-resort fallback for rows saved outside InventoryService (seed data, imports);
        // the service applies the configurable clinic.low-stock-default first.
        if (lowStockThreshold == null) lowStockThreshold = 10;
        if (active == null) active = true;
    }
}
