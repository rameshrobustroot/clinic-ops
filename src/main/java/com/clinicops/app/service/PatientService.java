package com.clinicops.app.service;

import com.clinicops.app.domain.Patient;
import com.clinicops.app.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final MedicalIdSequence medicalIdSequence;

    @Transactional(readOnly = true)
    public Page<Patient> search(Long clinicId, String query, int page) {
        Pageable pageable = PageRequest.of(page, 20);
        if (query == null || query.isBlank()) {
            return patientRepository.findByClinicIdOrderByUpdatedAtDesc(clinicId, pageable);
        }
        return patientRepository.search(clinicId, query.trim(), pageable);
    }

    @Transactional
    public Patient create(Long clinicId, Patient patient) {
        patient.setClinicId(clinicId);
        if (patient.getMrn() == null || patient.getMrn().isBlank()) {
            patient.setMrn(medicalIdSequence.nextPatientMrn());
        }
        return patientRepository.save(patient);
    }

    @Transactional(readOnly = true)
    public Patient getOrThrow(Long id) {
        Patient p = patientRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + id));
        return com.clinicops.app.tenant.TenantGuard.check(p, Patient::getClinicId);
    }

    @Transactional
    public Patient update(Long id, Patient updates) {
        Patient existing = getOrThrow(id);
        existing.setName(updates.getName());
        existing.setPhone(updates.getPhone());
        existing.setAge(updates.getAge());
        existing.setGender(updates.getGender());
        existing.setAddress(updates.getAddress());
        existing.setAllergies(updates.getAllergies());
        existing.setNotes(updates.getNotes());
        return patientRepository.save(existing);
    }
}
