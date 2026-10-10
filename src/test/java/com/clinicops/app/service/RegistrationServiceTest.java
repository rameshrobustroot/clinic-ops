package com.clinicops.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Clinic;
import com.clinicops.app.repository.AppUserRepository;
import com.clinicops.app.repository.ClinicRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RegistrationServiceTest {

    @Autowired RegistrationService registrationService;
    @Autowired ClinicRepository clinicRepository;
    @Autowired AppUserRepository userRepository;

    @Test
    void generateUniqueCode_slugifies_name() {
        String code = registrationService.generateUniqueCode("Happy Family Clinic");
        assertThat(code).isEqualTo("happy-family-clinic");
    }

    @Test
    void generateUniqueCode_appends_suffix_on_collision() {
        // First save a clinic with the slug
        clinicRepository.save(Clinic.builder().code("happy-clinic").name("Happy Clinic").build());

        // Second attempt with the same name should get -2 suffix
        String code = registrationService.generateUniqueCode("Happy Clinic");
        assertThat(code).isEqualTo("happy-clinic-2");
    }

    @Test
    void generateUniqueCode_strips_special_chars() {
        String code = registrationService.generateUniqueCode("Dr. Anna's Clinic & Pharmacy!!!");
        assertThat(code).matches("[a-z0-9_-]{3,32}");
        assertThat(code).startsWith("dr-anna-s-clinic-pharmacy");
    }

    @Test
    void register_creates_clinic_and_owner_atomically() {
        RegistrationService.RegistrationForm form = new RegistrationService.RegistrationForm();
        form.setClinicName("Test Clinic");
        form.setOwnerFullName("Test Owner");
        form.setUsername("owner1");
        form.setPassword("secret1");
        form.setConfirmPassword("secret1");
        form.setAcceptTerms(true);

        Clinic clinic = registrationService.register(form);

        assertThat(clinic.getId()).isNotNull();
        assertThat(clinic.getCode()).isEqualTo("test-clinic");
        assertThat(clinic.getTermsVersion()).isNotBlank();
        assertThat(clinic.getTermsAcceptedAt()).isNotNull();
        assertThat(userRepository.findByClinicCodeAndUsername("test-clinic", "owner1"))
                .isPresent()
                .get()
                .extracting(AppUser::getFullName)
                .isEqualTo("Test Owner");
    }

    @Test
    void register_rejects_when_terms_not_accepted() {
        RegistrationService.RegistrationForm form = new RegistrationService.RegistrationForm();
        form.setClinicName("Unaccepted Clinic");
        form.setOwnerFullName("Owner");
        form.setUsername("owner_x");
        form.setPassword("secret1");
        form.setConfirmPassword("secret1");
        // acceptTerms left false

        assertThatThrownBy(() -> registrationService.register(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Terms");
    }

    @Test
    void register_rejects_short_password() {
        RegistrationService.RegistrationForm form = new RegistrationService.RegistrationForm();
        form.setClinicName("Test Clinic 2");
        form.setOwnerFullName("Owner");
        form.setUsername("owner2");
        form.setPassword("short");
        form.setConfirmPassword("short");

        assertThatThrownBy(() -> registrationService.register(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Password");
    }

    @Test
    void register_rejects_invalid_username() {
        RegistrationService.RegistrationForm form = new RegistrationService.RegistrationForm();
        form.setClinicName("Another Clinic");
        form.setOwnerFullName("Owner");
        form.setUsername("has spaces");  // spaces don't match [a-z0-9._-]{3,32}
        form.setPassword("secret1");
        form.setConfirmPassword("secret1");

        assertThatThrownBy(() -> registrationService.register(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username");
    }
}
