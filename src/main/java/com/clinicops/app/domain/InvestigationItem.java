package com.clinicops.app.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "investigation_item")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestigationItem implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(nullable = false)
    private String name;

    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Soft-delete flag. Editing a visit marks superseded rows inactive instead of deleting them. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @PrePersist
    public void onCreate() {
        if (status == null) status = Status.PENDING;
        if (lineNo == null) lineNo = 1;
        if (active == null) active = true;
    }

    public enum Status { PENDING, COMPLETED, CANCELLED }
}
