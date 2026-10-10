package com.clinicops.app.service;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.domain.StockMovement;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.StockBatchRepository;
import com.clinicops.app.repository.StockMovementRepository;
import com.clinicops.app.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final MedicineRepository medicineRepository;
    private final StockBatchRepository stockBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierRepository supplierRepository;

    /** Applied when a medicine is saved without its own threshold (blank field on the form). */
    @org.springframework.beans.factory.annotation.Value("${clinic.low-stock-default:10}")
    private int lowStockDefault;

    @Transactional(readOnly = true)
    public List<Medicine> listMedicines(Long clinicId) {
        return medicineRepository.findByClinicIdOrderByNameAsc(clinicId);
    }

    @Transactional
    public Medicine createMedicine(Long clinicId, Medicine medicine) {
        // Always an INSERT: never trust a client-supplied id (would overwrite an existing row).
        medicine.setId(null);
        medicineRepository
                .findDuplicate(clinicId, medicine.getName(), medicine.getStrength(), medicine.getManufacturer())
                .ifPresent(existing -> {
                    throw new DuplicateMedicineException(existing);
                });
        medicine.setClinicId(clinicId);
        medicine.setActive(true);
        if (medicine.getLowStockThreshold() == null) medicine.setLowStockThreshold(lowStockDefault);
        return medicineRepository.save(medicine);
    }

    @Transactional
    public void softDeleteMedicine(Long id) {
        Medicine m = getOwnMedicineOrThrow(id);
        m.setActive(false);
        medicineRepository.save(m);
    }

    /** Thrown when a medicine with the same (name, strength, manufacturer) already exists in the clinic. */
    public static class DuplicateMedicineException extends RuntimeException {
        private final Long existingId;
        public DuplicateMedicineException(Medicine existing) {
            super("A medicine with the same name, strength and manufacturer already exists "
                    + "(" + existing.getName() + ").");
            this.existingId = existing.getId();
        }
        public Long getExistingId() { return existingId; }
    }

    @Transactional
    public Medicine updateMedicine(Long id, Medicine updates) {
        Medicine existing = getOwnMedicineOrThrow(id);
        medicineRepository
                .findDuplicate(existing.getClinicId(), updates.getName(), updates.getStrength(), updates.getManufacturer())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateMedicineException(other);
                });
        existing.setName(updates.getName());
        existing.setGenericName(updates.getGenericName());
        existing.setStrength(updates.getStrength());
        existing.setForm(updates.getForm());
        existing.setManufacturer(updates.getManufacturer());
        existing.setUnit(updates.getUnit());
        existing.setLowStockThreshold(updates.getLowStockThreshold() == null
                ? lowStockDefault : updates.getLowStockThreshold());
        return medicineRepository.save(existing);
    }

    @Transactional
    public void adjustBatch(Long batchId, Long userId, int newQuantity, String reason) {
        StockBatch batch = stockBatchRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("Batch not found: " + batchId));
        com.clinicops.app.tenant.TenantGuard.check(batch, StockBatch::getClinicId);
        int delta = newQuantity - batch.getQuantity();
        batch.setQuantity(newQuantity);
        stockBatchRepository.save(batch);

        if (delta != 0) {
            stockMovementRepository.save(StockMovement.builder()
                    .clinicId(batch.getClinicId())
                    .stockBatchId(batch.getId())
                    .movementType(StockMovement.MovementType.ADJUSTMENT)
                    .quantity(Math.abs(delta))
                    .notes(reason)
                    .createdBy(userId)
                    .build());
        }
    }

    @Transactional
    public void writeOffExpired(Long clinicId, Long userId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        for (StockBatch batch : stockBatchRepository.findExpired(clinicId, today)) {
            if (batch.getQuantity() == null || batch.getQuantity() <= 0) continue;
            int qty = batch.getQuantity();
            batch.setQuantity(0);
            stockBatchRepository.save(batch);
            stockMovementRepository.save(StockMovement.builder()
                    .clinicId(batch.getClinicId())
                    .stockBatchId(batch.getId())
                    .movementType(StockMovement.MovementType.EXPIRED)
                    .quantity(qty)
                    .notes("Auto write-off on expiry")
                    .createdBy(userId)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Medicine> search(Long clinicId, String query, int page) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, 50);
        if (query == null || query.isBlank()) {
            // small clinic: just return all sorted
            List<Medicine> all = medicineRepository.findByClinicIdOrderByNameAsc(clinicId);
            return new org.springframework.data.domain.PageImpl<>(all);
        }
        return medicineRepository.search(clinicId, query.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Medicine getMedicineOrThrow(Long id) {
        Medicine m = medicineRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Medicine not found: " + id));
        return com.clinicops.app.tenant.TenantGuard.check(m, Medicine::getClinicId);
    }

    /**
     * Like {@link #getMedicineOrThrow} but for writes: platform-catalog medicines (clinic_id NULL)
     * are readable by every clinic yet editable by none — a rename or delete would hit all tenants.
     */
    @Transactional(readOnly = true)
    public Medicine getOwnMedicineOrThrow(Long id) {
        Medicine m = getMedicineOrThrow(id);
        if (m.getClinicId() == null) {
            throw new PlatformMedicineReadOnlyException();
        }
        return m;
    }

    /** Thrown when a clinic tries to modify a shared platform-catalog medicine. */
    public static class PlatformMedicineReadOnlyException extends RuntimeException {
        public PlatformMedicineReadOnlyException() {
            super("This medicine is from the shared catalog and can't be edited or deleted by a clinic.");
        }
    }

    @Transactional(readOnly = true)
    public List<StockBatch> batchesFor(Long medicineId) {
        return stockBatchRepository.findByMedicineIdOrderByExpiryDateAsc(medicineId);
    }

    @Transactional(readOnly = true)
    public Integer totalStock(Long medicineId) {
        return stockBatchRepository.totalStockForMedicine(medicineId);
    }

    @Transactional
    public StockBatch addBatch(Long clinicId, Long medicineId, Long userId, String batchNumber,
                               LocalDate expiryDate, Integer quantity, BigDecimal purchasePrice,
                               BigDecimal salePrice, Long supplierId) {
        // Verify the medicine exists and is accessible (platform or this clinic's own).
        // Stock batches always belong to the PURCHASING clinic, regardless of whether
        // the medicine is a platform entry (clinic_id = NULL) or a clinic-private one.
        getMedicineOrThrow(medicineId);

        StockBatch batch = StockBatch.builder()
                .clinicId(clinicId)
                .medicineId(medicineId)
                .batchNumber(batchNumber)
                .expiryDate(expiryDate)
                .quantity(quantity)
                .initialQuantity(quantity)
                .purchasePrice(purchasePrice)
                .salePrice(salePrice)
                .supplierId(supplierId)
                .build();
        StockBatch saved = stockBatchRepository.save(batch);

        stockMovementRepository.save(StockMovement.builder()
                .clinicId(clinicId)
                .stockBatchId(saved.getId())
                .movementType(StockMovement.MovementType.IN)
                .quantity(quantity)
                .referenceType("BATCH_RECEIVE")
                .createdBy(userId)
                .build());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<com.clinicops.app.domain.Supplier> suppliers(Long clinicId) {
        return supplierRepository.findByClinicIdOrderByNameAsc(clinicId);
    }
}
