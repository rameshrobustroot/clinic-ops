package com.clinicops.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Form-backing object for recording a payment against a supplier bill. */
@Getter
@Setter
public class BillPaymentForm {
    private LocalDate paidDate;
    private BigDecimal amount;
    private String paymentMode;
    private String referenceNo;
    private String notes;
}
