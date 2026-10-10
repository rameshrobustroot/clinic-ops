package com.clinicops.app.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "prescription_item")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionItem implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "visit_id", nullable = false)
    private Long visitId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    /** When linked to an inventory medicine. NULL for free-text / custom entries. */
    @Column(name = "medicine_id")
    private Long medicineId;

    /** Free-text medicine name (set when medicineId is null, but may also be set for display). */
    @Column(name = "custom_name")
    private String customName;

    /** Dosage pattern, e.g. "1-0-1". */
    private String dosage;

    /** Frequency notes, e.g. "BD", "after food", "SOS". */
    private String frequency;

    /** Duration, e.g. "5 days". */
    private String duration;

    /** Free-text additional instructions. */
    private String instructions;

    /** Soft-delete flag. Editing a visit marks superseded rows inactive instead of deleting them. */
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}
