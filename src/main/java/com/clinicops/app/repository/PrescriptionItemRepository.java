package com.clinicops.app.repository;

import com.clinicops.app.domain.PrescriptionItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {

    /** Active items only — superseded rows stay in DB for audit. */
    List<PrescriptionItem> findByVisitIdAndActiveTrueOrderByLineNoAsc(Long visitId);

    /** Legacy alias — kept so existing callers still work. */
    default List<PrescriptionItem> findByVisitIdOrderByLineNoAsc(Long visitId) {
        return findByVisitIdAndActiveTrueOrderByLineNoAsc(visitId);
    }
}
