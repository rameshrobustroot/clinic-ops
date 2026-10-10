package com.clinicops.app.repository;

import com.clinicops.app.domain.InvestigationItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestigationItemRepository extends JpaRepository<InvestigationItem, Long> {

    /** Active items only — superseded rows stay in DB for audit. */
    List<InvestigationItem> findByVisitIdAndActiveTrueOrderByLineNoAsc(Long visitId);

    /** Legacy alias — kept so existing callers still work. */
    default List<InvestigationItem> findByVisitIdOrderByLineNoAsc(Long visitId) {
        return findByVisitIdAndActiveTrueOrderByLineNoAsc(visitId);
    }
}
