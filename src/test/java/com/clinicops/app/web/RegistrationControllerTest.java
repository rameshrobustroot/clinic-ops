package com.clinicops.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clinicops.app.domain.Clinic;
import com.clinicops.app.repository.ClinicRepository;
import com.clinicops.app.security.AppUserDetailsService;
import com.clinicops.app.security.CurrentUser;
import com.clinicops.app.security.SecurityConfig;
import com.clinicops.app.service.RegistrationService;
import com.clinicops.app.service.RegistrationService.RegistrationForm;
import com.clinicops.app.service.TermsService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Controller-layer tests for {@link RegistrationController} — public clinic sign-up. */
@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    @Autowired MockMvc mvc;

    @MockBean RegistrationService registrationService;
    @MockBean CurrentUser currentUser;
    @MockBean AppUserDetailsService appUserDetailsService;
    @MockBean TermsService termsService;
    @MockBean ClinicRepository clinicRepository;

    private static MockHttpServletRequestBuilder validRegistration() {
        return post("/register").with(csrf())
                .param("clinicName", "Sunrise Clinic")
                .param("clinicPhone", "9876543210")
                .param("clinicAddress", "1 Main St")
                .param("ownerFullName", "Dr Asha")
                .param("username", "asha")
                .param("password", "secret123")
                .param("confirmPassword", "secret123")
                .param("acceptTerms", "true");
    }

    @Nested
    class GetForm {

        @Test
        void form_is_public_and_has_empty_form() throws Exception {
            mvc.perform(get("/register"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/register"))
                    .andExpect(model().attributeExists("form"));
        }
    }

    @Nested
    class Submit {

        @Test
        void valid_submission_redirects_to_login_with_clinic_code() throws Exception {
            when(registrationService.register(any()))
                    .thenReturn(Clinic.builder().id(5L).code("sunrise").build());

            mvc.perform(validRegistration())
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?registered&code=sunrise"));

            ArgumentCaptor<RegistrationForm> captor = ArgumentCaptor.forClass(RegistrationForm.class);
            verify(registrationService).register(captor.capture());
            RegistrationForm form = captor.getValue();
            assertThat(form.getClinicName()).isEqualTo("Sunrise Clinic");
            assertThat(form.getUsername()).isEqualTo("asha");
            assertThat(form.getOwnerFullName()).isEqualTo("Dr Asha");
            assertThat(form.isAcceptTerms()).isTrue();
        }

        @Test
        void mismatched_passwords_rerender_form_without_calling_service() throws Exception {
            mvc.perform(validRegistration().param("confirmPassword", "different"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/register"))
                    .andExpect(model().attribute("error", "Password and confirmation do not match."))
                    .andExpect(content().string(
                            Matchers.containsString("Password and confirmation do not match.")));

            verify(registrationService, never()).register(any());
        }

        @Test
        void missing_password_rerenders_form() throws Exception {
            mvc.perform(post("/register").with(csrf()).param("clinicName", "X"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/register"))
                    .andExpect(model().attributeExists("error"));

            verify(registrationService, never()).register(any());
        }

        @Test
        void service_rejection_rerenders_form_with_message_and_keeps_input() throws Exception {
            when(registrationService.register(any()))
                    .thenThrow(new IllegalArgumentException("Clinic code already taken"));

            mvc.perform(validRegistration())
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/register"))
                    .andExpect(model().attribute("error", "Clinic code already taken"))
                    .andExpect(model().attribute("form",
                            Matchers.hasProperty("clinicName", Matchers.equalTo("Sunrise Clinic"))));
        }

        @Test
        void post_without_csrf_is_forbidden() throws Exception {
            mvc.perform(post("/register").param("clinicName", "X"))
                    .andExpect(status().isForbidden());

            verify(registrationService, never()).register(any());
        }
    }
}
