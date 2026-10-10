package com.clinicops.app.service;

import com.clinicops.app.domain.Doctor;
import com.clinicops.app.repository.DoctorRepository;
import com.clinicops.app.tenant.TenantGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final MedicalMasterService masterService;

    /** Active doctors only — used for visit-form dropdown. */
    @Transactional(readOnly = true)
    public List<Doctor> listActive(Long clinicId) {
        return doctorRepository.findByClinicIdAndActiveTrueOrderByFullNameAsc(clinicId);
    }

    /** All doctors including inactive — used for the admin CRUD list. */
    @Transactional(readOnly = true)
    public List<Doctor> listAll(Long clinicId) {
        return doctorRepository.findByClinicIdOrderByFullNameAsc(clinicId);
    }

    @Transactional(readOnly = true)
    public Doctor getOrThrow(Long id) {
        Doctor d = doctorRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Doctor not found: " + id));
        return TenantGuard.check(d, Doctor::getClinicId);
    }

    @Transactional
    public Doctor create(Long clinicId, Doctor incoming) {
        incoming.setId(null);
        incoming.setClinicId(clinicId);
        incoming.setActive(true);
        incoming.setQualification(masterService.normalizeQualification(clinicId, incoming.getQualification()));
        incoming.setSpecialization(masterService.normalizeSpecialization(clinicId, incoming.getSpecialization()));
        return doctorRepository.save(incoming);
    }

    @Transactional
    public Doctor update(Long id, Doctor incoming) {
        Doctor existing = getOrThrow(id);
        existing.setFullName(incoming.getFullName());
        existing.setQualification(
                masterService.normalizeQualification(existing.getClinicId(), incoming.getQualification()));
        existing.setRegistrationNo(incoming.getRegistrationNo());
        existing.setSpecialization(
                masterService.normalizeSpecialization(existing.getClinicId(), incoming.getSpecialization()));
        existing.setPhone(incoming.getPhone());
        existing.setEmail(incoming.getEmail());
        return doctorRepository.save(existing);
    }

    @Transactional
    public void softDelete(Long id) {
        Doctor d = getOrThrow(id);
        d.setActive(false);
        doctorRepository.save(d);
    }

    @Transactional
    public void restore(Long id) {
        Doctor d = getOrThrow(id);
        d.setActive(true);
        doctorRepository.save(d);
    }
}
