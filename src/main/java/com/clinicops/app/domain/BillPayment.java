package com.clinicops.app.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "bill_payment")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillPayment implements com.clinicops.app.tenant.TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @Column(name = "bill_id", nullable = false)
    private Long billId;

    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_mode", length = 30)
    private String paymentMode;

    @Column(name = "reference_no", length = 100)
    private String referenceNo;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
