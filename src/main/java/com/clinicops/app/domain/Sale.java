package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "sale")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sale implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "bill_no", unique = true)
    private String billNo;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "visit_id")
    private Long visitId;

    /** Sum of sale_item.line_total — i.e. after per-line discounts, before bill-level discount. */
    @Column(name = "subtotal_amount", nullable = false)
    private BigDecimal subtotalAmount;

    /** PERCENT or AMOUNT (null = no whole-bill discount). */
    @Column(name = "bill_discount_type", length = 10)
    private String billDiscountType;

    /** Raw value the user entered (% or ₹) for the whole-bill discount. */
    @Column(name = "bill_discount_value")
    private BigDecimal billDiscountValue;

    /** Resolved ₹ off at bill level. */
    @Column(name = "bill_discount_amount", nullable = false)
    private BigDecimal billDiscountAmount;

    /** subtotal − bill_discount = grand total. */
    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "payment_mode")
    private String paymentMode;

    @Column(name = "walk_in_name")
    private String walkInName;

    @Column(name = "walk_in_phone")
    private String walkInPhone;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (subtotalAmount == null) subtotalAmount = BigDecimal.ZERO;
        if (billDiscountAmount == null) billDiscountAmount = BigDecimal.ZERO;
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;
        if (paymentMode == null) paymentMode = "CASH";
    }

}
