package com.clinicops.app.service;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.repository.ClinicRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ClinicProfileService {

    private final ClinicRepository clinicRepository;
    private final FileStorageService fileStorage;

    @Transactional(readOnly = true)
    public Clinic getOrThrow(Long clinicId) {
        Clinic c = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicId));
        return com.clinicops.app.tenant.TenantGuard.check(c, Clinic::getId);
    }

    @Transactional
    public Clinic updateProfile(Long clinicId, ProfileForm form, MultipartFile logoFile) throws IOException {
        Clinic c = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicId));
        com.clinicops.app.tenant.TenantGuard.check(c, Clinic::getId);

        if (form.getName() != null && !form.getName().isBlank()) {
            c.setName(form.getName().trim());
        }
        c.setPhone(blankToNull(form.getPhone()));
        c.setAddress(blankToNull(form.getAddress()));
        c.setTagline(blankToNull(form.getTagline()));
        c.setEmail(blankToNull(form.getEmail()));
        c.setWebsite(blankToNull(form.getWebsite()));
        c.setLicenseNo(blankToNull(form.getLicenseNo()));
        c.setGstin(blankToNull(form.getGstin()));
        c.setFooterNote(blankToNull(form.getFooterNote()));

        if (logoFile != null && !logoFile.isEmpty()) {
            String stored = fileStorage.store(logoFile);
            c.setLogoPath(stored);
        }

        return clinicRepository.save(c);
    }

    @Transactional
    public void removeLogo(Long clinicId) {
        Clinic c = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NotFoundException("Clinic not found: " + clinicId));
        com.clinicops.app.tenant.TenantGuard.check(c, Clinic::getId);
        c.setLogoPath(null);
        clinicRepository.save(c);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    @lombok.Data
    public static class ProfileForm {
        private String name;
        private String phone;
        private String address;
        private String tagline;
        private String email;
        private String website;
        private String licenseNo;
        private String gstin;
        private String footerNote;
    }
}
