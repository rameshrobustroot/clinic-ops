package com.clinicops.app.repository;

import com.clinicops.app.domain.BillPayment;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillPaymentRepository extends JpaRepository<BillPayment, Long> {

    List<BillPayment> findByBillIdOrderByPaidDateDesc(Long billId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM BillPayment p WHERE p.billId = :billId")
    BigDecimal sumByBillId(@Param("billId") Long billId);
}
