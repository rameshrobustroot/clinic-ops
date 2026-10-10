package com.clinicops.app.repository;

import com.clinicops.app.domain.ProcurementItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementItemRepository extends JpaRepository<ProcurementItem, Long> {

    List<ProcurementItem> findByClinicIdAndStatusOrderByCreatedAtDesc(
            Long clinicId, ProcurementItem.Status status);

    List<ProcurementItem> findByClinicIdOrderByCreatedAtDesc(Long clinicId);

    long countByClinicIdAndStatus(Long clinicId, ProcurementItem.Status status);
}
