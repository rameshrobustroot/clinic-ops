package com.clinicops.app.service;

import com.clinicops.app.domain.ProcurementItem;
import com.clinicops.app.repository.ProcurementItemRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementService {

    private final ProcurementItemRepository repository;

    @Transactional(readOnly = true)
    public List<ProcurementItem> pending(Long clinicId) {
        return repository.findByClinicIdAndStatusOrderByCreatedAtDesc(clinicId, ProcurementItem.Status.PENDING);
    }

    @Transactional(readOnly = true)
    public List<ProcurementItem> all(Long clinicId) {
        return repository.findByClinicIdOrderByCreatedAtDesc(clinicId);
    }

    @Transactional(readOnly = true)
    public long pendingCount(Long clinicId) {
        return repository.countByClinicIdAndStatus(clinicId, ProcurementItem.Status.PENDING);
    }

    @Transactional
    public ProcurementItem addManual(Long clinicId, Long userId, String medicineName, String notes) {
        ProcurementItem item = ProcurementItem.builder()
                .clinicId(clinicId)
                .medicineName(medicineName)
                .source(ProcurementItem.Source.MANUAL)
                .status(ProcurementItem.Status.PENDING)
                .notes(notes)
                .createdBy(userId)
                .build();
        return repository.save(item);
    }

    @Transactional
    public ProcurementItem updateStatus(Long id, ProcurementItem.Status status) {
        ProcurementItem item = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Procurement item not found: " + id));
        com.clinicops.app.tenant.TenantGuard.check(item, ProcurementItem::getClinicId);
        item.setStatus(status);
        return repository.save(item);
    }
}
