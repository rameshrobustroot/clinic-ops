package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "supplier_bill")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierBill implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "bill_number", length = 100)
    private String billNumber;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "UNPAID";

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "attachment_path", length = 512)
    private String attachmentPath;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /** Outstanding balance: total - paid. */
    public BigDecimal getBalance() {
        BigDecimal paid = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        BigDecimal total = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        return total.subtract(paid);
    }

    /** True when the bill has a due date in the past and is not fully paid. */
    public boolean isOverdue() {
        return dueDate != null
                && LocalDate.now().isAfter(dueDate)
                && !"PAID".equals(status);
    }
}
