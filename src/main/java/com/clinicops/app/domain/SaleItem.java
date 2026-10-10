package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "sale_item")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleItem implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "stock_batch_id", nullable = false)
    private Long stockBatchId;

    @Column(name = "medicine_id", nullable = false)
    private Long medicineId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    /** qty × unit_price — the original line amount before any discount. */
    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    /** PERCENT or AMOUNT (null = no line discount). */
    @Column(name = "discount_type", length = 10)
    private String discountType;

    /** Raw value the user entered (% or ₹) — preserved for receipt reprinting. */
    @Column(name = "discount_value")
    private BigDecimal discountValue;

    /** Resolved ₹ off this item (after proportional split when a line spans batches). */
    @Builder.Default
    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** gross_amount − discount_amount — the amount actually billed for this item. */
    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;
}
