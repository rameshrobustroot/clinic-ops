package com.clinicops.app.service;

import com.clinicops.app.domain.MedicalDegree;
import com.clinicops.app.domain.MedicalInvestigation;
import com.clinicops.app.domain.MedicalSpecialization;
import com.clinicops.app.repository.MedicalDegreeRepository;
import com.clinicops.app.repository.MedicalInvestigationRepository;
import com.clinicops.app.repository.MedicalSpecializationRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + auto-add helpers for the three master lists (degrees, specializations,
 * investigations).
 *
 * <p>Scope model: each row is either <strong>platform-wide</strong>
 * ({@code clinicId == null}, curated by PLATFORM_ADMIN and visible to every clinic) or
 * <strong>clinic-specific</strong> ({@code clinicId != null}, visible only to that clinic).
 * Visit-form dropdowns show the union of both for the requesting clinic.
 *
 * <p>Authorization is enforced by the callers (controllers); this service just exposes
 * the right knobs for each scope.
 */
@Service
@RequiredArgsConstructor
public class MedicalMasterService {

    private final MedicalDegreeRepository degreeRepository;
    private final MedicalSpecializationRepository specRepository;
    private final MedicalInvestigationRepository investigationRepository;

    // ===================================================================
    // Degrees
    // ===================================================================

    /** Active platform + this clinic's rows. Used by the visit/doctor form dropdown. */
    @Transactional(readOnly = true)
    public List<MedicalDegree> listActiveDegrees(Long clinicId) {
        return degreeRepository.findActiveAvailableTo(clinicId);
    }

    /** All platform + this clinic's rows (including inactive). Used by clinic CRUD page. */
    @Transactional(readOnly = true)
    public List<MedicalDegree> listAllDegrees(Long clinicId) {
        return degreeRepository.findAllAvailableTo(clinicId);
    }

    /** Platform rows only — used by PLATFORM_ADMIN's editor. */
    @Transactional(readOnly = true)
    public List<MedicalDegree> listPlatformDegrees() {
        return degreeRepository.findAllPlatform();
    }

    @Transactional(readOnly = true)
    public MedicalDegree getDegreeOrThrow(Long id) {
        return degreeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Degree not found: " + id));
    }

    /** Clinic-scoped create — first checks the platform list (so doctors can't shadow a
     *  platform value with a duplicate clinic row). */
    @Transactional
    public MedicalDegree createClinicDegree(Long clinicId, String name) {
        String clean = requireName(name);
        if (degreeRepository.findPlatformByNameIgnoreCase(clean).isPresent()) {
            return degreeRepository.findPlatformByNameIgnoreCase(clean).get();
        }
        Optional<MedicalDegree> existing = degreeRepository.findClinicByNameIgnoreCase(clinicId, clean);
        if (existing.isPresent()) return existing.get();
        return degreeRepository.save(MedicalDegree.builder()
                .clinicId(clinicId).name(clean).active(true).build());
    }

    /** Platform-scoped create. Only PLATFORM_ADMIN should reach this. */
    @Transactional
    public MedicalDegree createPlatformDegree(String name) {
        String clean = requireName(name);
        Optional<MedicalDegree> existing = degreeRepository.findPlatformByNameIgnoreCase(clean);
        if (existing.isPresent()) return existing.get();
        return degreeRepository.save(MedicalDegree.builder()
                .clinicId(null).name(clean).active(true).build());
    }

    @Transactional
    public MedicalDegree renameDegree(Long id, String newName) {
        MedicalDegree d = getDegreeOrThrow(id);
        String clean = requireName(newName);
        requireNoNameClash(id, d.getClinicId(), clean, degreeRepository::findPlatformByNameIgnoreCase,
                degreeRepository::findClinicByNameIgnoreCase, MedicalDegree::getId);
        d.setName(clean);
        return degreeRepository.save(d);
    }

    @Transactional
    public void softDeleteDegree(Long id) {
        MedicalDegree d = getDegreeOrThrow(id);
        d.setActive(false);
        degreeRepository.save(d);
    }

    @Transactional
    public void restoreDegree(Long id) {
        MedicalDegree d = getDegreeOrThrow(id);
        d.setActive(true);
        degreeRepository.save(d);
    }

    /**
     * Parse a comma-separated qualification string and ensure each value exists in the
     * <em>this clinic's view</em> of the catalogue (platform + clinic). Any new value
     * is auto-inserted as a <strong>clinic-specific</strong> row. Never promotes to platform.
     */
    @Transactional
    public String normalizeQualification(Long clinicId, String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<String> tokens = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (tokens.isEmpty()) return null;
        for (String value : tokens) {
            createClinicDegree(clinicId, value);
        }
        return String.join(", ", tokens);
    }

    // ===================================================================
    // Specializations
    // ===================================================================

    @Transactional(readOnly = true)
    public List<MedicalSpecialization> listActiveSpecializations(Long clinicId) {
        return specRepository.findActiveAvailableTo(clinicId);
    }

    @Transactional(readOnly = true)
    public List<MedicalSpecialization> listAllSpecializations(Long clinicId) {
        return specRepository.findAllAvailableTo(clinicId);
    }

    @Transactional(readOnly = true)
    public List<MedicalSpecialization> listPlatformSpecializations() {
        return specRepository.findAllPlatform();
    }

    @Transactional(readOnly = true)
    public MedicalSpecialization getSpecializationOrThrow(Long id) {
        return specRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Specialization not found: " + id));
    }

    @Transactional
    public MedicalSpecialization createClinicSpecialization(Long clinicId, String name) {
        String clean = requireName(name);
        if (specRepository.findPlatformByNameIgnoreCase(clean).isPresent()) {
            return specRepository.findPlatformByNameIgnoreCase(clean).get();
        }
        Optional<MedicalSpecialization> existing = specRepository.findClinicByNameIgnoreCase(clinicId, clean);
        if (existing.isPresent()) return existing.get();
        return specRepository.save(MedicalSpecialization.builder()
                .clinicId(clinicId).name(clean).active(true).build());
    }

    @Transactional
    public MedicalSpecialization createPlatformSpecialization(String name) {
        String clean = requireName(name);
        Optional<MedicalSpecialization> existing = specRepository.findPlatformByNameIgnoreCase(clean);
        if (existing.isPresent()) return existing.get();
        return specRepository.save(MedicalSpecialization.builder()
                .clinicId(null).name(clean).active(true).build());
    }

    @Transactional
    public MedicalSpecialization renameSpecialization(Long id, String newName) {
        MedicalSpecialization s = getSpecializationOrThrow(id);
        String clean = requireName(newName);
        requireNoNameClash(id, s.getClinicId(), clean, specRepository::findPlatformByNameIgnoreCase,
                specRepository::findClinicByNameIgnoreCase, MedicalSpecialization::getId);
        s.setName(clean);
        return specRepository.save(s);
    }

    @Transactional
    public void softDeleteSpecialization(Long id) {
        MedicalSpecialization s = getSpecializationOrThrow(id);
        s.setActive(false);
        specRepository.save(s);
    }

    @Transactional
    public void restoreSpecialization(Long id) {
        MedicalSpecialization s = getSpecializationOrThrow(id);
        s.setActive(true);
        specRepository.save(s);
    }

    @Transactional
    public String normalizeSpecialization(Long clinicId, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        createClinicSpecialization(clinicId, clean);
        return clean;
    }

    // ===================================================================
    // Investigations
    // ===================================================================

    @Transactional(readOnly = true)
    public List<MedicalInvestigation> listActiveInvestigations(Long clinicId) {
        return investigationRepository.findActiveAvailableTo(clinicId);
    }

    @Transactional(readOnly = true)
    public List<MedicalInvestigation> listAllInvestigations(Long clinicId) {
        return investigationRepository.findAllAvailableTo(clinicId);
    }

    @Transactional(readOnly = true)
    public List<MedicalInvestigation> listPlatformInvestigations() {
        return investigationRepository.findAllPlatform();
    }

    @Transactional(readOnly = true)
    public MedicalInvestigation getInvestigationOrThrow(Long id) {
        return investigationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Investigation not found: " + id));
    }

    @Transactional
    public MedicalInvestigation createClinicInvestigation(Long clinicId, String name) {
        String clean = requireName(name);
        if (investigationRepository.findPlatformByNameIgnoreCase(clean).isPresent()) {
            return investigationRepository.findPlatformByNameIgnoreCase(clean).get();
        }
        Optional<MedicalInvestigation> existing = investigationRepository.findClinicByNameIgnoreCase(clinicId, clean);
        if (existing.isPresent()) return existing.get();
        return investigationRepository.save(MedicalInvestigation.builder()
                .clinicId(clinicId).name(clean).active(true).build());
    }

    @Transactional
    public MedicalInvestigation createPlatformInvestigation(String name) {
        String clean = requireName(name);
        Optional<MedicalInvestigation> existing = investigationRepository.findPlatformByNameIgnoreCase(clean);
        if (existing.isPresent()) return existing.get();
        return investigationRepository.save(MedicalInvestigation.builder()
                .clinicId(null).name(clean).active(true).build());
    }

    @Transactional
    public MedicalInvestigation renameInvestigation(Long id, String newName) {
        MedicalInvestigation i = getInvestigationOrThrow(id);
        String clean = requireName(newName);
        requireNoNameClash(id, i.getClinicId(), clean, investigationRepository::findPlatformByNameIgnoreCase,
                investigationRepository::findClinicByNameIgnoreCase, MedicalInvestigation::getId);
        i.setName(clean);
        return investigationRepository.save(i);
    }

    @Transactional
    public void softDeleteInvestigation(Long id) {
        MedicalInvestigation i = getInvestigationOrThrow(id);
        i.setActive(false);
        investigationRepository.save(i);
    }

    @Transactional
    public void restoreInvestigation(Long id) {
        MedicalInvestigation i = getInvestigationOrThrow(id);
        i.setActive(true);
        investigationRepository.save(i);
    }

    @Transactional
    public String normalizeInvestigation(Long clinicId, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String clean = raw.trim();
        createClinicInvestigation(clinicId, clean);
        return clean;
    }

    /**
     * A rename must not collide (case-insensitively, like the unique indexes) with another row in
     * the same scope - and a clinic row may not take a platform row's name, the same no-shadowing
     * rule the create methods apply. Renaming a row to a new casing of its own name is fine.
     */
    private static <T> void requireNoNameClash(Long id, Long clinicId, String name,
                                               java.util.function.Function<String, Optional<T>> platformByName,
                                               java.util.function.BiFunction<Long, String, Optional<T>> clinicByName,
                                               java.util.function.Function<T, Long> idOf) {
        Optional<T> clash = platformByName.apply(name);
        if (clash.isEmpty() && clinicId != null) clash = clinicByName.apply(clinicId, name);
        if (clash.isPresent() && !idOf.apply(clash.get()).equals(id)) {
            throw new IllegalArgumentException("\"" + name + "\" already exists.");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        return name.trim();
    }
}
