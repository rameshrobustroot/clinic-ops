package com.clinicops.app.service;

import com.clinicops.app.domain.Medicine;
import com.clinicops.app.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final MedicineRepository medicineRepository;

    @Transactional(readOnly = true)
    public DashboardView load(Long clinicId) {
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant sevenDaysAgo = LocalDate.now().minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant();

        long newPatientsToday = patientRepository.countByClinicIdAndCreatedAtGreaterThanEqual(clinicId, startOfDay);
        long visitsToday = visitRepository.countByClinicIdAndVisitDateGreaterThanEqual(clinicId, startOfDay);
        long salesCountToday = saleRepository.countByClinicIdAndCreatedAtGreaterThanEqual(clinicId, startOfDay);
        BigDecimal salesTotalToday = saleRepository.totalSince(clinicId, startOfDay);

        List<Object[]> topRaw = saleItemRepository.topMedicinesSince(clinicId, sevenDaysAgo);
        List<TopMedicine> top = new ArrayList<>();
        int limit = Math.min(5, topRaw.size());
        Set<Long> medIds = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            medIds.add((Long) topRaw.get(i)[0]);
        }
        Map<Long, Medicine> medById = new HashMap<>();
        if (!medIds.isEmpty()) {
            medicineRepository.findAllById(medIds).forEach(m -> medById.put(m.getId(), m));
        }
        for (int i = 0; i < limit; i++) {
            Long medId = (Long) topRaw.get(i)[0];
            long qty = ((Number) topRaw.get(i)[1]).longValue();
            Medicine m = medById.get(medId);
            top.add(new TopMedicine(medId, m != null ? m.getName() : "Medicine " + medId, qty));
        }

        return new DashboardView(newPatientsToday, visitsToday, salesCountToday, salesTotalToday, top);
    }

    public record TopMedicine(Long medicineId, String name, long quantitySold) {}

    public record DashboardView(
            long newPatientsToday,
            long visitsToday,
            long salesCountToday,
            BigDecimal salesTotalToday,
            List<TopMedicine> topMedicines7d) {}
}
