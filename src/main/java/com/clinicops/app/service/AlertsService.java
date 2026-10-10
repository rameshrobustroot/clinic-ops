package com.clinicops.app.service;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.domain.StockBatch;
import com.clinicops.app.repository.MedicineRepository;
import com.clinicops.app.repository.StockBatchRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlertsService {

    private final StockBatchRepository stockBatchRepository;
    private final MedicineRepository medicineRepository;

    @Value("${clinic.expiry-near-days:90}")
    private int nearDays;

    @Value("${clinic.expiry-critical-days:30}")
    private int criticalDays;

    @Value("${clinic.low-stock-default:10}")
    private int lowStockDefault;

    @Transactional(readOnly = true)
    public AlertsView load(Long clinicId) {
        LocalDate today = LocalDate.now();
        List<StockBatch> all = stockBatchRepository.findExpiringUpTo(clinicId, today.plusDays(nearDays));

        List<StockBatch> expired = new ArrayList<>();
        List<StockBatch> critical = new ArrayList<>();
        List<StockBatch> near = new ArrayList<>();
        for (StockBatch b : all) {
            if (b.getExpiryDate().isBefore(today)) expired.add(b);
            else if (!b.getExpiryDate().isAfter(today.plusDays(criticalDays))) critical.add(b);
            else near.add(b);
        }

        List<Medicine> meds = medicineRepository.findByClinicIdOrderByNameAsc(clinicId);
        // The list includes the whole shared platform catalog (clinic_id NULL). A catalog medicine
        // the clinic has never stocked isn't "low" - it's simply not carried - so only own medicines
        // and catalog medicines this clinic has had batches of are checked.
        Set<Long> carried = stockBatchRepository.findMedicineIdsEverStocked(clinicId);
        List<LowStockEntry> low = new ArrayList<>();
        for (Medicine m : meds) {
            if (m.getClinicId() == null && !carried.contains(m.getId())) continue;
            Integer total = stockBatchRepository.totalStockForMedicine(clinicId, m.getId());
            int threshold = m.getLowStockThreshold() == null ? lowStockDefault : m.getLowStockThreshold();
            if (total == null) total = 0;
            if (total <= threshold) low.add(new LowStockEntry(m, total, threshold));
        }

        Map<Long, Medicine> medById = new HashMap<>();
        for (Medicine m : meds) medById.put(m.getId(), m);

        Map<Long, Long> daysToExpiry = new HashMap<>();
        for (StockBatch b : all) {
            daysToExpiry.put(b.getId(), ChronoUnit.DAYS.between(today, b.getExpiryDate()));
        }

        return new AlertsView(expired, critical, near, low, medById, daysToExpiry);
    }

    public record LowStockEntry(Medicine medicine, int currentStock, int threshold) {}

    public record AlertsView(
            List<StockBatch> expired,
            List<StockBatch> critical,
            List<StockBatch> near,
            List<LowStockEntry> lowStock,
            Map<Long, Medicine> medicinesById,
            Map<Long, Long> daysToExpiry) {}
}
