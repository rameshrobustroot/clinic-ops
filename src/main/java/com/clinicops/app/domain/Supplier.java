package com.clinicops.app.domain;

import com.clinicops.app.util.ValidationPatterns;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import lombok.*;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "supplier")
@Filter(name = "tenantFilter", condition = "clinic_id = :tenantId")
@jakarta.persistence.EntityListeners(com.clinicops.app.tenant.TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Supplier implements com.clinicops.app.tenant.TenantScoped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @NotBlank(message = "Name is required")
    @Column(nullable = false)
    private String name;

    @Pattern(regexp = ValidationPatterns.PHONE_INDIA_OPTIONAL, message = "Enter a 10-digit mobile starting with 6/7/8/9")
    private String phone;

    private String address;

    // Bank transfer
    @Column(name = "bank_account_name")
    private String bankAccountName;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Pattern(regexp = ValidationPatterns.IFSC, message = "IFSC must be 11 characters, e.g. HDFC0001234")
    @Column(name = "bank_ifsc")
    private String bankIfsc;

    @Column(name = "bank_name")
    private String bankName;

    // UPI
    @Column(name = "upi_id")
    private String upiId;

    // Tax / freeform
    @Pattern(regexp = ValidationPatterns.GSTIN, message = "Enter a valid 15-character GSTIN, e.g. 22AAAAA0000A1Z5")
    private String gstin;

    private String notes;

    /**
     * Accumulated credit from overpayments on previous bills.
     * Automatically applied when a new bill is recorded for this supplier.
     */
    @Column(name = "credit_balance", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal creditBalance = BigDecimal.ZERO;
}
