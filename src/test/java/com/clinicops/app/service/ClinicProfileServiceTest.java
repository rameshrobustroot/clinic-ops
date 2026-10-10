package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.tenant.CrossTenantAccessException;
import com.clinicops.app.tenant.TenantContext;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ClinicProfileServiceTest {

    @Autowired ClinicProfileService service;
    @Autowired ClinicRepository clinicRepository;
    @Autowired FileStorageService fileStorage;

    Long clinicId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        clinicId = clinicRepository.save(Clinic.builder()
                .code("prof-" + System.nanoTime()).name("Original Name")
                .phone("1111111111").logoPath("old/logo.png").build()).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ClinicProfileService.ProfileForm form() {
        ClinicProfileService.ProfileForm f = new ClinicProfileService.ProfileForm();
        f.setName("  New Name  ");
        f.setPhone(" 9876543210 ");
        f.setAddress("  12 Main St ");
        f.setTagline("Care");
        f.setEmail("a@b.com");
        f.setWebsite("https://x.example");
        f.setLicenseNo("LIC-1");
        f.setGstin("29ABCDE1234F1Z5");
        f.setFooterNote("Thanks");
        return f;
    }

    @Test
    void update_profile_trims_all_fields() throws Exception {
        Clinic c = service.updateProfile(clinicId, form(), null);

        assertThat(c.getName()).isEqualTo("New Name");
        assertThat(c.getPhone()).isEqualTo("9876543210");
        assertThat(c.getAddress()).isEqualTo("12 Main St");
        assertThat(c.getGstin()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(c.getFooterNote()).isEqualTo("Thanks");
    }

    @Test
    void blank_name_keeps_existing_name() throws Exception {
        ClinicProfileService.ProfileForm f = form();
        f.setName("   ");

        Clinic c = service.updateProfile(clinicId, f, null);

        assertThat(c.getName()).isEqualTo("Original Name");
    }

    @Test
    void blank_optional_fields_are_cleared_to_null() throws Exception {
        ClinicProfileService.ProfileForm f = new ClinicProfileService.ProfileForm();
        f.setPhone("  ");
        f.setEmail("");

        Clinic c = service.updateProfile(clinicId, f, null);

        assertThat(c.getPhone()).isNull();
        assertThat(c.getEmail()).isNull();
        assertThat(c.getAddress()).isNull();
        assertThat(c.getName()).isEqualTo("Original Name");
    }

    @Test
    void no_logo_or_empty_logo_keeps_existing_logo_path() throws Exception {
        service.updateProfile(clinicId, form(), null);
        Clinic c = service.updateProfile(clinicId, form(),
                new MockMultipartFile("logo", "logo.png", "image/png", new byte[0]));

        assertThat(c.getLogoPath()).isEqualTo("old/logo.png");
    }

    @Test
    void uploaded_logo_is_stored_and_path_saved() throws Exception {
        Clinic c = service.updateProfile(clinicId, form(),
                new MockMultipartFile("logo", "Brand.PNG", "image/png", new byte[] {1, 2, 3}));

        assertThat(c.getLogoPath()).isNotEqualTo("old/logo.png").endsWith(".png");
        assertThat(Files.exists(fileStorage.resolve(c.getLogoPath()))).isTrue();
        Files.deleteIfExists(fileStorage.resolve(c.getLogoPath()));
    }

    @Test
    void remove_logo_clears_logo_path() {
        service.removeLogo(clinicId);

        assertThat(clinicRepository.findById(clinicId).orElseThrow().getLogoPath()).isNull();
    }

    @Test
    void unknown_clinic_throws_illegal_argument() {
        assertThatThrownBy(() -> service.getOrThrow(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Clinic not found");
        assertThatThrownBy(() -> service.updateProfile(-1L, form(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.removeLogo(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void other_tenant_cannot_read_or_update_clinic_profile() {
        Long otherClinic = clinicRepository.save(Clinic.builder()
                .code("prof-o-" + System.nanoTime()).name("Other").build()).getId();

        TenantContext.set(otherClinic);
        assertThatThrownBy(() -> service.getOrThrow(clinicId))
                .isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> service.updateProfile(clinicId, form(), null))
                .isInstanceOf(CrossTenantAccessException.class);
        assertThatThrownBy(() -> service.removeLogo(clinicId))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    void own_tenant_can_read_profile() {
        TenantContext.set(clinicId);
        assertThat(service.getOrThrow(clinicId).getName()).isEqualTo("Original Name");
    }
}
