package com.clinicops.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Form-backing object for creating a new supplier bill. */
@Getter
@Setter
public class SupplierBillForm {
    private Long supplierId;
    private String billNumber;
    private LocalDate billDate;
    private LocalDate dueDate;
    private BigDecimal totalAmount;
    private String notes;
}
