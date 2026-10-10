package com.clinicops.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.clinicops.app.domain.AppUser;
import com.clinicops.app.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.GrantedAuthority;

class AppUserDetailsTest {

    private AppUser user(Role role) {
        return AppUser.builder()
                .id(5L).clinicId(9L).username("asha").passwordHash("{hash}")
                .fullName("Asha K").role(role).enabled(true).build();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void authority_is_role_name_with_ROLE_prefix(Role role) {
        AppUserDetails d = new AppUserDetails(user(role));

        assertThat(d.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role.name());
    }

    @Test
    void delegates_identity_fields_to_user() {
        AppUserDetails d = new AppUserDetails(user(Role.DOCTOR));

        assertThat(d.getUsername()).isEqualTo("asha");
        assertThat(d.getPassword()).isEqualTo("{hash}");
        assertThat(d.getUserId()).isEqualTo(5L);
        assertThat(d.getClinicId()).isEqualTo(9L);
        assertThat(d.getFullName()).isEqualTo("Asha K");
    }

    @Test
    void account_is_never_expired_locked_or_credentials_expired() {
        AppUserDetails d = new AppUserDetails(user(Role.OWNER));

        assertThat(d.isAccountNonExpired()).isTrue();
        assertThat(d.isAccountNonLocked()).isTrue();
        assertThat(d.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void is_enabled_is_null_safe() {
        AppUser u = user(Role.OWNER);

        u.setEnabled(true);
        assertThat(new AppUserDetails(u).isEnabled()).isTrue();
        u.setEnabled(false);
        assertThat(new AppUserDetails(u).isEnabled()).isFalse();
        u.setEnabled(null);
        assertThat(new AppUserDetails(u).isEnabled()).isFalse();
    }

    @Test
    void theme_defaults_to_violet_when_null_or_blank() {
        AppUser u = user(Role.OWNER);

        u.setTheme(null);
        assertThat(new AppUserDetails(u).getTheme()).isEqualTo("violet");
        u.setTheme("  ");
        assertThat(new AppUserDetails(u).getTheme()).isEqualTo("violet");
        u.setTheme("sky");
        assertThat(new AppUserDetails(u).getTheme()).isEqualTo("sky");
    }

    @Test
    void platform_admin_has_null_clinic_id() {
        AppUser u = user(Role.PLATFORM_ADMIN);
        u.setClinicId(null);

        assertThat(new AppUserDetails(u).getClinicId()).isNull();
    }
}
